package com.azentio.wiregate.service;

import com.azentio.wiregate.api.WireConflictException;
import com.azentio.wiregate.api.WireNotFoundException;
import com.azentio.wiregate.api.WirePageResponse;
import com.azentio.wiregate.api.WireReason;
import com.azentio.wiregate.api.WireRequest;
import com.azentio.wiregate.api.WireResponse;
import com.azentio.wiregate.api.WireStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WireDecisionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RiskRuleEvaluator riskRuleEvaluator;
    private final FundsDecisionEvaluator fundsDecisionEvaluator;
    private final RowMapper<WireResponse> responseMapper;

    public WireDecisionService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            RiskRuleEvaluator riskRuleEvaluator,
            FundsDecisionEvaluator fundsDecisionEvaluator) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.riskRuleEvaluator = riskRuleEvaluator;
        this.fundsDecisionEvaluator = fundsDecisionEvaluator;
        this.responseMapper = (rs, rowNum) -> {
            try {
                return objectMapper.readValue(rs.getString(1), WireResponse.class);
            } catch (JsonProcessingException exception) {
                throw new SQLException("Stored wire response could not be decoded.", exception);
            }
        };
    }

    @Transactional
    public WireResponse decide(WireRequest request) {
        UUID uetr = UUID.fromString(request.uetr());
        lockUetr(uetr);
        WireResponse existing = findExisting(uetr, request);
        if (existing != null) {
            return existing;
        }

        BigDecimal fee = fundsDecisionEvaluator.feeFor(request.chargeBearer());

        FundsDecisionEvaluator.AccountSnapshot account = lockAccount(request.debtor().accountId());
        WireStatus status = WireStatus.REJECTED;
        List<WireReason> reasons = new ArrayList<>();
        String corridor = RiskRuleEvaluator.corridor(request.debtorAgentBic(), request.creditor().country());
        Optional<WireReason> fundsFailure = fundsDecisionEvaluator.evaluate(
                account, request.currency(), request.amount(), fee,
                () -> dailyAmount(request.debtor().accountId(), request.createdAt()));
        if (fundsFailure.isPresent()) {
            reasons.add(fundsFailure.get());
        } else {
            reasons = riskRuleEvaluator.evaluate(request);
            status = reasons.isEmpty() ? WireStatus.APPROVED : WireStatus.HELD;
        }

        BigDecimal earmarkedAmount = status == WireStatus.REJECTED
                ? BigDecimal.ZERO : request.amount().add(fee);
        Long alertId = null;
        WireResponse response = new WireResponse(
                uetr,
                status,
                List.copyOf(reasons),
                fee,
                earmarkedAmount,
                mask(request.debtor().accountId()),
                mask(request.creditor().accountId()),
                corridor,
                null);

        insertPayment(request, response);
        if (status != WireStatus.REJECTED) {
            int updated = jdbc.update("""
                    UPDATE accounts
                    SET available_balance = available_balance - ?
                    WHERE account_id = ? AND status = 'ACTIVE' AND available_balance >= ?
                    """, earmarkedAmount, request.debtor().accountId(), earmarkedAmount);
            if (updated != 1) {
                throw new IllegalStateException("Locked debtor account changed while earmarking funds.");
            }
            jdbc.update("""
                    INSERT INTO funds_earmark (uetr, account_id, amount, fee, business_date)
                    VALUES (?, ?, ?, ?, ?)
                    """, uetr, request.debtor().accountId(), request.amount(), fee,
                    request.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate());
        }
        if (status == WireStatus.HELD) {
            Long id = jdbc.queryForObject("""
                    INSERT INTO alert (uetr, status) VALUES (?, 'OPEN') RETURNING id
                    """, Long.class, uetr);
            response = new WireResponse(uetr, status, List.copyOf(reasons), fee, earmarkedAmount,
                    mask(request.debtor().accountId()), mask(request.creditor().accountId()), corridor, id);
            jdbc.update("UPDATE wire_payment SET response_payload = ?::jsonb WHERE uetr = ?",
                    toJson(response), uetr);
        }
        return response;
    }

    @Transactional(readOnly = true)
    public WireResponse get(UUID uetr) {
        List<WireResponse> found = jdbc.query(
                "SELECT response_payload::text FROM wire_payment WHERE uetr = ?",
                responseMapper, uetr);
        if (found.isEmpty()) {
            throw new WireNotFoundException("No wire exists for UETR " + uetr + ".");
        }
        return found.get(0);
    }

    @Transactional(readOnly = true)
    public WirePageResponse list(WireStatus status, int size, String cursor) {
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        List<WireResponse> found;
        if (cursor == null || cursor.isBlank()) {
            found = jdbc.query("""
                    SELECT response_payload::text FROM wire_payment
                    WHERE status = ? ORDER BY created_at DESC, id DESC LIMIT ?
                    """, responseMapper, status.name(), size + 1);
        } else {
            Cursor decoded = decodeCursor(cursor);
            found = jdbc.query("""
                    SELECT response_payload::text FROM wire_payment
                    WHERE status = ? AND (created_at, id) < (?, ?)
                    ORDER BY created_at DESC, id DESC LIMIT ?
                    """, responseMapper, status.name(), decoded.createdAt(), decoded.id(), size + 1);
        }
        String nextCursor = null;
        if (found.size() > size) {
            found = new ArrayList<>(found.subList(0, size));
            WireResponse last = found.get(found.size() - 1);
            nextCursor = cursorFor(last.uetr());
        }
        return new WirePageResponse(List.copyOf(found), nextCursor);
    }

    private WireResponse findExisting(UUID uetr, WireRequest request) {
        List<ExistingPayment> existing = jdbc.query("""
                SELECT request_payload::text, response_payload::text
                FROM wire_payment WHERE uetr = ?
                """, (rs, rowNum) -> new ExistingPayment(rs.getString(1), rs.getString(2)), uetr);
        if (existing.isEmpty()) {
            return null;
        }
        try {
            JsonNode stored = objectMapper.readTree(existing.get(0).requestJson());
            JsonNode incoming = objectMapper.valueToTree(request);
            if (!stored.equals(incoming)) {
                throw new WireConflictException("This UETR was already used with a different payload.");
            }
            return objectMapper.readValue(existing.get(0).responseJson(), WireResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored wire payload could not be decoded.", exception);
        }
    }

    private void lockUetr(UUID uetr) {
        jdbc.execute((Connection connection) -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, uetr.toString());
                statement.execute();
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not acquire the UETR idempotency lock.", exception);
            }
            return null;
        });
    }

    private FundsDecisionEvaluator.AccountSnapshot lockAccount(String accountId) {
        List<FundsDecisionEvaluator.AccountSnapshot> accounts = jdbc.query("""
                SELECT currency, available_balance, daily_outward_limit, status
                FROM accounts WHERE account_id = ? FOR UPDATE
                """, (rs, rowNum) -> new FundsDecisionEvaluator.AccountSnapshot(rs.getString("currency"),
                rs.getBigDecimal("available_balance"), rs.getBigDecimal("daily_outward_limit"),
                rs.getString("status")), accountId);
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private BigDecimal dailyAmount(String accountId, OffsetDateTime createdAt) {
        BigDecimal amount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM funds_earmark
                WHERE account_id = ? AND business_date = ?
                """, BigDecimal.class, accountId,
                createdAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate());
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private void insertPayment(WireRequest request, WireResponse response) {
        jdbc.update("""
                INSERT INTO wire_payment
                    (uetr, msg_id, created_at, amount, currency, charge_bearer,
                     debtor_account_id, creditor_account_id, status, fee, reasons,
                     request_payload, response_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                """,
                response.uetr(), request.msgId(), request.createdAt(), request.amount(),
                request.currency(), request.chargeBearer().name(), request.debtor().accountId(),
                request.creditor().accountId(), response.status().name(), response.fee(),
                toJson(response.reasons()), toJson(request), toJson(response));
    }

    private String cursorFor(UUID uetr) {
        CursorData data = jdbc.queryForObject(
                "SELECT created_at, id FROM wire_payment WHERE uetr = ?",
                (rs, rowNum) -> new CursorData(rs.getObject("created_at", OffsetDateTime.class), rs.getLong("id")),
                uetr);
        String raw = data.createdAt() + "|" + data.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            return new Cursor(OffsetDateTime.parse(decoded.substring(0, separator)),
                    Long.parseLong(decoded.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor is invalid");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize wire data.", exception);
        }
    }

    private static String mask(String accountId) {
        return "****" + accountId.substring(accountId.length() - 4);
    }

    private record ExistingPayment(String requestJson, String responseJson) {
    }

    private record Cursor(OffsetDateTime createdAt, long id) {
    }

    private record CursorData(OffsetDateTime createdAt, long id) {
    }
}
