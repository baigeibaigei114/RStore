"""部署后的只读检查，不创建业务资源；登录凭据从进程环境读取。"""
import argparse
import json
import os

import requests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task-id", type=int, required=True)
    parser.add_argument("--base-url", default="http://[::1]:8080/api")
    args = parser.parse_args()
    with requests.Session() as session:
        session.trust_env = False
        unauth = session.get(args.base_url + "/monitoring-regions", timeout=10)
        assert unauth.status_code == 401 or unauth.json().get("code") == 401
        login = session.post(args.base_url + "/auth/login", json={
            "username": os.environ["BASELINE_USERNAME"],
            "password": os.environ["BASELINE_PASSWORD"],
        }, timeout=15).json()
        assert login["code"] == 200
        session.headers["Authorization"] = "Bearer " + login["data"]["accessToken"]
        try:
            regions = session.get(args.base_url + "/monitoring-regions", timeout=10).json()
            assert regions["code"] == 200
            task = session.get(args.base_url + f"/tasks/{args.task_id}", timeout=10).json()["data"]
            snapshot = json.loads(task["params"])["regionSnapshot"]
            assert task["status"] == "SUCCESS"
            region = session.get(args.base_url + f"/monitoring-regions/{snapshot['regionId']}",
                                 timeout=10).json()
            assert region["code"] == 200
            result = session.get(args.base_url + f"/tasks/{args.task_id}/result", timeout=10).json()["data"]
            metadata = json.loads(result["resultMetadata"])
            assert metadata["schemaVersion"] == 2 and metadata["regionSnapshot"] == snapshot
            assert result["status"] == "PUBLISHED"
            print(f"PASS deployed API: anonymous rejected, region persisted, task {args.task_id} SUCCESS/PUBLISHED")
        finally:
            session.post(args.base_url + "/auth/logout", timeout=10)


if __name__ == "__main__":
    main()
