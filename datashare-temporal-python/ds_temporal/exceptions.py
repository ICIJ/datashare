class DependencyInjectionError(RuntimeError):
    def __init__(self, name: str):
        msg = f"{name} was not injected"
        super().__init__(msg)
