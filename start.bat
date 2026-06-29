@echo off
cd /d "%~dp0"

echo Starting backend...
start "Backend" cmd /c "cd backend && mvn spring-boot:run"

echo Starting frontend...
start "Frontend" cmd /c "cd frontend && npm run dev"

echo.
echo Both services started in separate windows.
echo Backend:  http://localhost:8080
echo Frontend: http://localhost:3000
echo Swagger:  http://localhost:8080/swagger-ui/index.html
echo.
echo Close the service windows to stop, or run stop.bat
