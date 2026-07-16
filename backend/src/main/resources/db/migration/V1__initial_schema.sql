-- CryptoPal initial schema.
--
-- PostgreSQL is the source of truth for every durable financial fact: who a user is,
-- how much virtual fiat they hold, what crypto they own, and every trade they have
-- made. Redis owns none of this. It only ever caches sessions and the latest prices,
-- both of which are rebuildable.
--
-- Every money and quantity column is NUMERIC, never float. NUMERIC is exact, so a
-- balance cannot drift the way binary floating point does, and it maps cleanly to
-- BigDecimal on the Java side.
--
-- The three scales, kept together so the reasoning is in one place:
--
--   numeric(20,2)  fiat amounts in virtual USD. A cent is the smallest unit that means
--                  anything, so amounts are held to 2 decimals.
--   numeric(38,8)  crypto quantities. 8 decimals is the crypto convention (1 satoshi).
--   numeric(20,8)  prices. A price is a rate, not an amount, so it keeps 8 decimals
--                  rather than 2: XRP trades near 0.50, and rounding the rate to cents
--                  would make quantity * execution_price disagree with the fiat that
--                  actually moved, leaving the stored trade internally inconsistent.
--
-- gen_random_uuid() is built into PostgreSQL 13 and later, so no pgcrypto extension is
-- needed. The defaults mean a plain SQL insert works without the application present,
-- which keeps this schema testable on its own.

create table users (
    id            uuid primary key default gen_random_uuid(),
    -- Email is the login identity, so the database enforces uniqueness rather than
    -- trusting the service to check first. That check would otherwise be a race:
    -- two simultaneous registrations could both look, both see nothing, and both insert.
    email         varchar(255) not null unique,
    -- Wide enough for a BCrypt hash (60 chars today) with room to spare. The plain
    -- password is never stored, and never logged.
    password_hash varchar(255) not null,
    created_at    timestamptz  not null default now()
);

create table wallets (
    id           uuid primary key default gen_random_uuid(),
    -- unique, not just a reference: one wallet per user, enforced by the database.
    -- on delete cascade mirrors the UML, where User composes Wallet, so the wallet's
    -- lifetime genuinely belongs to the user.
    user_id      uuid not null unique references users (id) on delete cascade,
    -- The randomized starting balance lands here at registration. The check is the
    -- last line of defence against a trade overdrawing an account: even if the service
    -- logic were wrong, the database would refuse the write.
    fiat_balance numeric(20, 2) not null check (fiat_balance >= 0),
    created_at   timestamptz    not null default now(),
    updated_at   timestamptz    not null default now()
);

create table holdings (
    id         uuid primary key default gen_random_uuid(),
    wallet_id  uuid not null references wallets (id) on delete cascade,
    symbol     varchar(16)   not null,
    -- >= 0 rather than > 0: selling a position down to exactly zero is normal, and
    -- leaving the row in place keeps the holding's history simple.
    quantity   numeric(38, 8) not null check (quantity >= 0),
    updated_at timestamptz    not null default now(),
    -- One row per asset per wallet. Without this, a concurrent buy of the same symbol
    -- could create a second row and quietly split the position in two.
    constraint holdings_wallet_symbol_unique unique (wallet_id, symbol)
);

create table transactions (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references users (id) on delete cascade,
    symbol          varchar(16)    not null,
    -- Stored as text with a check rather than a native enum, so adding a side later
    -- is a one-line migration instead of an ALTER TYPE.
    side            varchar(4)     not null check (side in ('BUY', 'SELL')),
    -- All three are strictly positive: this table only records trades that succeeded,
    -- and a zero-quantity or zero-value trade is a bug, not a real event.
    quantity        numeric(38, 8) not null check (quantity > 0),
    execution_price numeric(20, 8) not null check (execution_price > 0),
    fiat_amount     numeric(20, 2) not null check (fiat_amount > 0),
    created_at      timestamptz    not null default now()
);

-- Transaction history is always read as "this user's most recent trades", both for the
-- history endpoint and for the AI context. Ordering the index by created_at desc lets
-- Postgres read the newest rows straight off the front instead of sorting every time.
create index transactions_user_id_created_at_idx
    on transactions (user_id, created_at desc);

create table price_snapshots (
    id          uuid primary key default gen_random_uuid(),
    symbol      varchar(16)    not null,
    price       numeric(20, 8) not null check (price > 0),
    captured_at timestamptz    not null default now()
);

-- Snapshots are written every 15 seconds and read as "the recent history of one
-- symbol", so this index matches that access pattern for the same reason as above.
create index price_snapshots_symbol_captured_at_idx
    on price_snapshots (symbol, captured_at desc);
