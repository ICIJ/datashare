set /p pid=< RUNNING_PID
taskkill /F /PID %pid%
del RUNNING_PID
