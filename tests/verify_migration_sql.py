"""Run every supported upgrade on SQLite and compare each step with Room exports."""
from pathlib import Path
import json
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / 'core/data/schemas/ru.liftzip.orders.data.database.OrdersDatabase'
SOURCE = (ROOT / 'core/data/src/main/kotlin/ru/liftzip/orders/data/database/OrdersDatabase.kt').read_text()
CURRENT = int(re.search(r'const val SCHEMA_VERSION = (\d+)', SOURCE)[1])
assert 'version = OrdersDatabase.SCHEMA_VERSION' in SOURCE, 'Room and backup must use the same version'

def schema(version):
    return json.loads((SCHEMAS / f'{min(version, 6)}.json').read_text())['database']

def create(db, definition):
    for table in definition['entities']:
        db.execute(table['createSql'].replace('${TABLE_NAME}', table['tableName']))
        for index in table['indices']:
            db.execute(index['createSql'].replace('${TABLE_NAME}', table['tableName']))

def migrate(db, start):
    match = re.search(rf'val MIGRATION_{start}_{start + 1}\b(.*?)(?=\n        (?:val MIGRATION_|fun create\())', SOURCE, re.S)
    assert match, f'missing migration {start}->{start + 1}'
    body = match[1]
    loop = re.search(r'listOf\(([^)]+)\)\.forEach', body)
    columns = re.findall(r'"([^"]+)"', loop[1]) if loop else []
    statements = re.findall(r'db.execSQL\("([^"\n]+)"\)', body)
    assert statements, f'no SQL in migration {start}'
    if 'createFormationGuards(db)' in body:
        statements.extend([
            "CREATE TRIGGER IF NOT EXISTS settlement_groups_one_forming_insert BEFORE INSERT ON settlement_groups WHEN NEW.state = 'FORMING' AND EXISTS (SELECT 1 FROM settlement_groups WHERE state = 'FORMING') BEGIN SELECT RAISE(ABORT, 'Only one forming group is allowed'); END",
            "CREATE TRIGGER IF NOT EXISTS settlement_groups_one_forming_update BEFORE UPDATE OF state ON settlement_groups WHEN NEW.state = 'FORMING' AND EXISTS (SELECT 1 FROM settlement_groups WHERE state = 'FORMING' AND id != OLD.id) BEGIN SELECT RAISE(ABORT, 'Only one forming group is allowed'); END",
        ])
    for statement in statements:
        if '$it' in statement:
            assert columns
            for column in columns:
                db.execute(statement.replace('$it', column))
        else:
            db.execute(statement)

def compare(db, version):
    expected = sqlite3.connect(':memory:')
    definition = schema(version)
    create(expected, definition)
    if version >= 7:
        expected.execute('ALTER TABLE order_lines ADD COLUMN productUrl TEXT')
    if version >= 9:
        expected.execute('ALTER TABLE order_lines ADD COLUMN actualWeight TEXT')
    for table in definition['entities']:
        name = table['tableName']
        for pragma in ('table_info', 'foreign_key_list'):
            assert db.execute(f'PRAGMA {pragma}("{name}")').fetchall() == expected.execute(f'PRAGMA {pragma}("{name}")').fetchall(), (version, name, pragma)
        def indices(conn):
            return sorted((row[1], row[2], tuple(conn.execute(f'PRAGMA index_info("{row[1]}")'))) for row in conn.execute(f'PRAGMA index_list("{name}")') if row[3] != 'pk' and not (row[1] == 'index_settlement_groups_one_forming'))
        assert indices(db) == indices(expected), (version, name, 'indices')
    assert not db.execute('PRAGMA foreign_key_check').fetchall()
    expected.close()

for start in range(1, CURRENT):
    db = sqlite3.connect(':memory:')
    db.execute('PRAGMA foreign_keys=ON')
    create(db, schema(start))
    if start >= 7:
        db.execute('ALTER TABLE order_lines ADD COLUMN productUrl TEXT')
    if start >= 9:
        db.execute('ALTER TABLE order_lines ADD COLUMN actualWeight TEXT')
    if start >= 8:
        db.executescript("""
            CREATE TRIGGER settlement_groups_one_forming_insert BEFORE INSERT ON settlement_groups
            WHEN NEW.state = 'FORMING' AND EXISTS (SELECT 1 FROM settlement_groups WHERE state = 'FORMING')
            BEGIN SELECT RAISE(ABORT, 'Only one forming group is allowed'); END;
            CREATE TRIGGER settlement_groups_one_forming_update BEFORE UPDATE OF state ON settlement_groups
            WHEN NEW.state = 'FORMING' AND EXISTS (SELECT 1 FROM settlement_groups WHERE state = 'FORMING' AND id != OLD.id)
            BEGIN SELECT RAISE(ABORT, 'Only one forming group is allowed'); END;
        """)
    db.execute("INSERT INTO settlement_groups VALUES('group',1,'CALCULATING',0,1,1,1,1)")
    db.execute("INSERT INTO products VALUES('product','Товар',NULL,NULL,NULL,'[]',1,1,1)")
    db.execute("INSERT INTO orders(id,number,date,counterparty,groupId,receivedPaymentMinor,comment,costsConfirmed,calculationState,createdAt,updatedAt,revision) VALUES('order',1,'2026-11-11','Тест','group',500000,'original',0,'INCOMPLETE',1,1,1)")
    db.execute("INSERT INTO order_lines(id,orderId,productId,productNameSnapshot,articleSnapshot,quantity,saleUnitPriceMinor,purchaseUnitPrice,purchaseCurrency,purchaseRate,createdAt,updatedAt,revision) VALUES('line','order','product','Товар',NULL,'2',250000,'100','CNY','13.25',1,1,1)")
    db.execute("INSERT INTO cost_entries VALUES('legacy-delivery','ORDER','order','DELIVERY','350','RUB','1',1,'2026-11-11','legacy',1,1,1)")
    if 2 <= start < 8:
        db.execute("CREATE UNIQUE INDEX index_settlement_groups_one_forming ON settlement_groups(state) WHERE state='FORMING'")
    if start >= 3:
        db.execute("UPDATE order_lines SET deliveryWeight='2',deliveryTariff='25',deliveryRate='13.25',deliveryPlanDate='2026-11-11'")
    if start >= 4:
        db.execute('UPDATE orders SET manualDeliveryPriceMinor=0')
    if start >= 5:
        db.execute("UPDATE orders SET orderRate='13.25'")
    for version in range(start, CURRENT):
        migrate(db, version)
        compare(db, version + 1)
    # The v8 migration removes the Room-invisible partial index.
    assert not db.execute("SELECT 1 FROM sqlite_master WHERE type='index' AND name='index_settlement_groups_one_forming'").fetchone()
    assert db.execute("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='settlement_groups_one_forming_insert'").fetchone()
    assert db.execute("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='settlement_groups_one_forming_update'").fetchone()
    assert db.execute('SELECT receivedPaymentMinor,comment FROM orders').fetchone() == (500000, 'original')
    assert db.execute('SELECT withdrawn,transferred,received FROM orders').fetchone() == (0, 0, 0)
    assert db.execute('SELECT orderRate FROM orders').fetchone() == (('13.25' if start >= 3 else None),)
    assert db.execute('SELECT manualDeliveryPriceMinor FROM orders').fetchone() == ((0 if start >= 4 else None),)
    assert db.execute("SELECT amount FROM cost_entries WHERE id='legacy-delivery'").fetchone() == ('350',)
    assert db.execute('SELECT count(*) FROM delivery_payments').fetchone() == (0,)
    assert db.execute('SELECT productUrl FROM order_lines WHERE id=\'line\'').fetchone() == (None,)
    db.close()
    print(f'PASS: {start}->{CURRENT}; schemas, amounts, rates, null/zero, legacy costs and empty action marks preserved')
