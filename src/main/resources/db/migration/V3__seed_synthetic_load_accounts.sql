INSERT INTO accounts (account_id, currency, available_balance, daily_outward_limit, status)
SELECT to_char(200000000000 + account_number, 'FM000000000000'),
       'INR',
       1000000000.00,
       1000000000.00,
       'ACTIVE'
FROM generate_series(1, 200) AS sequence(account_number);
