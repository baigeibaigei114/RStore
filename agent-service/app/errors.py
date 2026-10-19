class AgentError(Exception):
    def __init__(self, code: str, message: str, status: int = 400, retryable: bool = False, trace=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.retryable = retryable
        self.trace = trace
