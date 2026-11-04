"""Linux-native, killable stage execution. The parent owns MQ and all lease callbacks."""
import ctypes
import multiprocessing
import os
import signal
import time

from workers.lease_execution import LeaseLost


def _child(connection, function, parent_pid):
    os.setsid()
    # An orphan native decoder must not survive a supervisor crash.
    if ctypes.CDLL(None).prctl(1, signal.SIGKILL) != 0:
        os._exit(70)
    if os.getppid() != parent_pid:
        os._exit(1)
    import resource
    resource.setrlimit(resource.RLIMIT_AS, (2816 * 1024**2, 2816 * 1024**2))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    os.environ.update(GDAL_NUM_THREADS="1", OPJ_NUM_THREADS="1", GDAL_CACHEMAX="128")
    try:
        result = function()
        connection.send((True, result))
    except BaseException as error:
        # Preserve exception class, but never move a traceback or arbitrary path to the server.
        connection.send((False, "INVALID_REQUEST" if isinstance(error, ValueError) else "PROCESS_FAILED"))
    finally:
        connection.close()


def _stop(process):
    if process.is_alive():
        try:
            if os.getpgid(process.pid) == process.pid:
                os.killpg(process.pid, signal.SIGTERM)
            else:
                process.terminate()
        except ProcessLookupError:
            pass
        process.join(10)
    if process.is_alive():
        try:
            if os.getpgid(process.pid) == process.pid:
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
        except ProcessLookupError:
            pass
        process.join(2)
    if process.is_alive():
        raise LeaseLost("Native child termination could not be confirmed")


def execute_process_with_lease(function, heartbeat, pump, *, deadline_seconds, stopped, heartbeat_seconds=20):
    """stopped is called only after join proves termination, including lease loss/errors."""
    if os.name != "posix":
        raise LeaseLost("Ingestion supervisor requires the supported Linux Worker")
    context = multiprocessing.get_context("fork")
    reader, writer = context.Pipe(duplex=False)
    process = context.Process(target=_child, args=(writer, function, os.getpid()), daemon=False)
    process.start()
    writer.close()
    deadline, next_heartbeat = time.monotonic() + deadline_seconds, time.monotonic() + heartbeat_seconds
    killed = False
    try:
        while not reader.poll():
            if not process.is_alive():
                raise LeaseLost("Native stage exited without a result")
            if time.monotonic() >= deadline:
                raise LeaseLost("Native stage hard deadline exceeded")
            try:
                pump()
            except Exception as error:
                raise LeaseLost("Native stage lost its execution connection") from error
            if time.monotonic() >= next_heartbeat:
                try:
                    heartbeat()
                except Exception as error:
                    raise LeaseLost("Native stage lost its lease") from error
                next_heartbeat = time.monotonic() + heartbeat_seconds
        success, result = reader.recv()
        process.join(1)
        if process.is_alive():
            raise LeaseLost("Native stage did not exit after reporting")
        if not success:
            if result == "INVALID_REQUEST":
                raise ValueError("Native stage rejected input")
            raise RuntimeError("Native stage failed")
        return result
    finally:
        killed = process.is_alive()
        _stop(process)
        reader.close()
        stopped("KILLED" if killed else "EXITED")
