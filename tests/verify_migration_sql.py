"""Execute actual 2->3 migration SQL on a schema-2 fixture; compare SQLite structures with Room's schema-3 export."""
from pathlib import Path
import json, re, sqlite3
ROOT=Path(__file__).resolve().parents[1]
schemas=ROOT/'core/data/schemas/ru.liftzip.orders.data.database.OrdersDatabase'
old=json.loads((schemas/'2.json').read_text())['database']
new=json.loads((schemas/'3.json').read_text())['database']
def create(db,schema):
 for table in schema['entities']:
  db.execute(table['createSql'].replace('${TABLE_NAME}',table['tableName']))
  for idx in table['indices']: db.execute(idx['createSql'].replace('${TABLE_NAME}',table['tableName']))
actual=sqlite3.connect(':memory:');actual.execute('PRAGMA foreign_keys=ON');create(actual,old)
actual.execute("INSERT INTO settlement_groups VALUES('group',1,'FORMING',0,1,NULL,1,1)")
actual.execute("INSERT INTO products VALUES('product','Товар',NULL,NULL,NULL,'[]',1,1,1)")
actual.execute("INSERT INTO orders VALUES('order',1,'2026-11-11','Денис','group',500000,'original',0,'INCOMPLETE',1,1,1)")
actual.execute("INSERT INTO order_lines VALUES('line','order','product','Товар',NULL,'2',250000,'100','CNY','13.25',1,1,1)")
actual.execute("INSERT INTO cost_entries VALUES('legacy-delivery','ORDER','order','DELIVERY','350','RUB','1',1,'2026-11-11','legacy',1,1,1)")
actual.execute("CREATE UNIQUE INDEX index_settlement_groups_one_forming ON settlement_groups(state) WHERE state='FORMING'")
source=(ROOT/'core/data/src/main/kotlin/ru/liftzip/orders/data/database/OrdersDatabase.kt').read_text()
body=source.split('val MIGRATION_2_3')[1].split('fun create(')[0]
for statement in re.findall(r'db.execSQL\("([^"\n]+)"\)',body):
 if '$it' in statement:
  for column in ['deliveryWeight','deliveryTariff','deliveryRate','deliveryPlanDate']:actual.execute(statement.replace('$it',column))
 else: actual.execute(statement)
expected=sqlite3.connect(':memory:');create(expected,new)
for table in new['entities']:
 name=table['tableName']
 for pragma in ['table_info','foreign_key_list']:
  assert actual.execute(f'PRAGMA {pragma}("{name}")').fetchall()==expected.execute(f'PRAGMA {pragma}("{name}")').fetchall(),(name,pragma)
 def indices(db):
  return sorted((row[1],row[2],tuple(db.execute(f'PRAGMA index_info("{row[1]}")').fetchall())) for row in db.execute(f'PRAGMA index_list("{name}")') if row[3]!='pk')
 assert indices(actual)==indices(expected),name
assert actual.execute('SELECT receivedPaymentMinor,comment FROM orders').fetchone()==(500000,'original')
assert actual.execute('SELECT deliveryWeight,deliveryTariff,deliveryRate,deliveryPlanDate FROM order_lines').fetchone()==(None,None,None,None)
assert actual.execute("SELECT amount FROM cost_entries WHERE id='legacy-delivery'").fetchone()==('350',)
assert not actual.execute('PRAGMA foreign_key_check').fetchall()
print('PASS: actual migration SQL matches Room schema 3; order/line/legacy costs preserved; no fabricated forecasts/payments')
