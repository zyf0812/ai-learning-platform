$root = Split-Path -Parent $MyInvocation.MyCommand.Definition

Write-Host "Starting backend..."
$bp = Start-Process -WindowStyle Hidden -PassThru powershell "-NoProfile -Command cd '$root\backend'; mvn spring-boot:run"

Write-Host "Starting frontend..."
$fp = Start-Process -WindowStyle Hidden -PassThru powershell "-NoProfile -Command cd '$root\frontend'; npm run dev"

Write-Host ""
Write-Host "Backend:  http://localhost:8080"
Write-Host "Frontend: http://localhost:3000"
Write-Host "Swagger:  http://localhost:8080/swagger-ui/index.html"
Write-Host ""
Write-Host "Press Enter to stop all services..."
Read-Host

$bp.Kill()
$fp.Kill()
Write-Host "Services stopped."
