package com.azentio.wiregate.api;

import com.azentio.wiregate.service.WireDecisionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wires")
public class WireController {
    private final WireDecisionService decisionService;

    public WireController(WireDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public WireResponse create(@Valid @RequestBody WireRequest request) {
        return decisionService.decide(request);
    }

    @GetMapping("/{uetr}")
    public WireResponse get(@PathVariable UUID uetr) {
        return decisionService.get(uetr);
    }

    @GetMapping
    public WirePageResponse list(
            @RequestParam(defaultValue = "HELD") WireStatus status,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor) {
        return decisionService.list(status, size, cursor);
    }
}
