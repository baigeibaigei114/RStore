"""Reserve a conservative bound before each paid validation action; never retries a provider call."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess

LEDGER = Path('/opt/ndvi-demo/feature-validation-budget.json')


def calls():
    # ready.modelCalls=0 describes the readiness check itself, not a lifetime counter.
    # Persisted Java MODEL operations provide auditable execution accounting.
    return int(subprocess.check_output(['sudo', '-n', 'docker', 'exec', 'rs-demo-postgres-1',
               'psql', '-U', 'rsdemo', '-d', 'rs_demo', '-Atc',
               "SELECT count(*) FROM ai_ndvi_step WHERE kind='MODEL'"], text=True))


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='action', required=True)
    reserve = sub.add_parser('reserve')
    reserve.add_argument('--label', required=True)
    reserve.add_argument('--max-calls', type=int, choices=range(1, 13), required=True)
    finish = sub.add_parser('finish')
    finish.add_argument('--stateless-success', action='store_true')
    sub.add_parser('status')
    args = parser.parse_args()
    ledger = json.loads(LEDGER.read_text()) if LEDGER.exists() else {
        'authorizedCapCny': 5, 'authorization': 'user explicit CNY 5 validation approval 2026-11-09',
        'reservationPerModelCallCny': 0.1, 'reservedCny': 0, 'actions': [],
        'pricingSource': 'https://api-docs.deepseek.com/quick_start/pricing/',
        'basis': 'Flash peak USD .3/M input, 1.2/M output; input <=24KiB plus protocol allowance, output <=1200; 10CNY/USD conservative bound',
        'notAnInvoice': True}
    pending = next((row for row in ledger['actions'] if row['status'] == 'reserved'), None)
    current = calls()
    if args.action == 'reserve':
        assert pending is None, 'Finish the prior bounded action before reserving another'
        cents = sum(row['maxCalls'] * 10 for row in ledger['actions']) + args.max_calls * 10
        assert cents <= 450, 'Validation reservation limit reached; preserve at least .5 CNY margin'
        ledger['actions'].append({'label': args.label, 'maxCalls': args.max_calls, 'beforeCalls': current,
                                  'status': 'reserved', 'startedAt': datetime.now(timezone.utc).isoformat()})
        ledger['reservedCny'] = cents / 100
    elif args.action == 'finish':
        assert pending is not None, 'No pending paid action'
        delta = current - pending['beforeCalls']
        assert 0 <= delta <= pending['maxCalls'], 'Observed provider calls exceeded this action reservation'
        observed = 1 if args.stateless_success else delta
        if args.stateless_success:
            assert pending['maxCalls'] == 1 and pending['label'] == 'public-rag-question'
        pending.update(status='finished', observedCalls=observed, executionModelOpDelta=delta,
                       countEvidence='successful bounded stateless answer' if args.stateless_success else 'persisted execution MODEL operation delta',
                       finishedAt=datetime.now(timezone.utc).isoformat())
    LEDGER.write_text(json.dumps(ledger, ensure_ascii=False, indent=2))
    os.chmod(LEDGER, 0o600)
    print(json.dumps({'authorizedCapCny': 5, 'reservedCny': ledger['reservedCny'], 'persistedExecutionModelOps': current,
                      'actions': [{'label': row['label'], 'status': row['status'], 'maxCalls': row['maxCalls'],
                                   'observedCalls': row.get('observedCalls')} for row in ledger['actions']], 'notAnInvoice': True}))


if __name__ == '__main__':
    main()
