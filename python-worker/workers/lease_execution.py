"""计算线程不接触 Pika；调用线程持续处理连接事件并续租。"""
from concurrent.futures import Future
from threading import Thread
from time import monotonic


class LeaseLost(RuntimeError):
    """无法确认执行权：停止本 Worker，结果只能留在本次独立路径中。"""


def execute_with_lease(process, heartbeat, pump, lease_seconds, *, interval=None):
    result = Future()

    def run():
        try:
            result.set_result(process())
        except BaseException as error:
            result.set_exception(error)

    Thread(target=run, name="leased-raster", daemon=True).start()
    period = interval if interval is not None else min(20, lease_seconds / 3)
    next_heartbeat = monotonic() + period
    while not result.done():
        # Pika IO只能在创建连接的线程运行，pump 本身提供有界等待。
        try:
            pump()
            if monotonic() >= next_heartbeat:
                heartbeat()
                next_heartbeat = monotonic() + period
        except Exception as error:
            raise LeaseLost("执行连接或续租失败，停止当前 Worker") from error
    return result.result()
