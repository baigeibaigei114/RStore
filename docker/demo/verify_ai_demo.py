"""Bounded real AI validation through the public Java API; task confirmation remains explicit."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

from seed_demo_assets import BASE, DemoApi

REPORT = BASE / 'ai-feature-verification.json'
BUDGET = Path(__file__).with_name('validation_budget.py')


def persist(report):
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    REPORT.chmod(0o600)


def paid(api, path, body, label, maximum):
    subprocess.run(['python3', str(BUDGET), 'reserve', '--label', label, '--max-calls', str(maximum)], check=True)
    successful = False
    try:
        result = api.call(path, 'POST', body)
        successful = True
        return result
    finally:
        command = ['python3', str(BUDGET), 'finish']
        if path == '/agent/answers' and successful:
            command.append('--stateless-success')
        subprocess.run(command, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('prepare', 'advance', 'explain', 'answer', 'snapshot'))
    args = parser.parse_args()
    api = DemoApi('https://rs-api.example.com/api')
    report = json.loads(REPORT.read_text()) if REPORT.exists() else {}
    seed = json.loads((BASE / 'feature-seed-journal.json').read_text())['publicSamples']['dongting-autumn']
    if args.action == 'prepare' and not report.get('executionId'):
        report.setdefault('clientRequestId', str(uuid.uuid4()))
        persist(report)
        text = '请对“示例影像 · 洞庭湖湿地 2024-11-07”，使用“示例区域 · 洞庭湖湿地 2024-11-07”，计算单期区域NDVI。'
        view = paid(api, '/agent/executions', {'clientRequestId': report['clientRequestId'], 'text': text}, 'public-ai-create', 3)
        report['executionId'] = view['executionId']
        report['initialView'] = view
        persist(report)
    view = api.call('/agent/executions/' + report['executionId']) if report.get('executionId') else None
    if args.action == 'advance':
        assert view is not None
        if view['status'] == 'NEEDS_INPUT':
            body = {'clientRequestId': str(uuid.uuid4()), 'expectedVersion': view['version']}
            for field, candidates, expected in [('selectedImageId', 'imageCandidates', seed['imageId']),
                                                  ('selectedRegionId', 'regionCandidates', seed['regionId'])]:
                if any(str(row['id']) == str(expected) for row in view.get(candidates, [])):
                    body[field] = str(expected)
            body['text'] = '选择洞庭湖湿地2024-11-07的示例影像和同名示例区域。'
            view = paid(api, '/agent/executions/' + view['executionId'] + '/messages', body, 'public-ai-selection', 3)
        if view['status'] == 'WAITING_CONFIRMATION':
            draft = view['draft']
            assert str(draft['imageId']) == str(seed['imageId']) and str(draft['monitoringRegionId']) == str(seed['regionId'])
            report['confirmedDraft'] = draft
            persist(report)
            view = api.call('/agent/executions/' + view['executionId'] + '/confirm', 'POST',
                            {'draftVersion': draft['draftVersion'], 'draftHash': draft['draftHash']})
            for _ in range(120):
                view = api.call('/agent/executions/' + view['executionId'])
                if view['status'] not in ('WAITING_TASK', 'RUNNING'):
                    break
                time.sleep(1)
    if args.action == 'explain':
        assert view and view['status'] == 'READY_TO_EXPLAIN'
        view = paid(api, '/agent/executions/' + view['executionId'] + '/explanation',
                    {'clientRequestId': str(uuid.uuid4())}, 'public-ai-result-explanation', 1)
    if args.action == 'answer' and not report.get('answer'):
        report['question'] = 'NDVI 的计算公式是什么？'
        report['answer'] = paid(api, '/agent/answers', {'question': report['question']}, 'public-rag-question', 1)
    if view:
        report['currentView'] = view
        report['steps'] = api.call('/agent/executions/' + view['executionId'] + '/steps')
        if view.get('task'):
            task_id = view['task']['taskId']
            report['task'] = api.call('/tasks/' + str(task_id))
            if view['task']['status'] == 'SUCCESS':
                report['result'] = api.call('/tasks/' + str(task_id) + '/result')
        print(json.dumps({'executionId': view['executionId'], 'status': view['status'], 'question': view.get('question'),
                          'missingFields': view.get('missingFields'), 'imageCandidates': view.get('imageCandidates'),
                          'regionCandidates': view.get('regionCandidates'), 'draft': view.get('draft'),
                          'task': view.get('task'), 'error': view.get('error'),
                          'hasExplanation': bool(view.get('explanation'))}, ensure_ascii=False), flush=True)
    if report.get('answer'):
        print('REAL_RAG_ANSWER_SAVED', flush=True)
    persist(report)


if __name__ == '__main__':
    main()
