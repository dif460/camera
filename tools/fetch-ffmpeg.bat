@echo off
setlocal
cd /d "%~dp0"

if exist ffmpeg.exe (
    echo ffmpeg.exe already exists.
    exit /b 0
)

set "URL=https://files.pythonhosted.org/packages/bb/df/4a55891802fb1e745c2852cd958e3d104dfbb46cf4b45f859d4f5006320b/imageio_ffmpeg-0.8.0-py3-none-win_amd64.whl"

echo Downloading FFmpeg (~38 MB, from imageio-ffmpeg 0.8.0)...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $tmp=Join-Path $env:TEMP 'cama-ffmpeg'; New-Item -ItemType Directory -Force -Path $tmp | Out-Null; $zip=Join-Path $tmp 'ffmpeg.zip'; Invoke-WebRequest -Uri '%URL%' -OutFile $zip; Expand-Archive -Force -Path $zip -DestinationPath $tmp; Copy-Item (Join-Path $tmp 'imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v9.0.2.exe') (Join-Path (Get-Location) 'ffmpeg.exe') -Force; Remove-Item -Recurse -Force $tmp"

if errorlevel 1 (
    echo.
    echo Download failed. Download FFmpeg manually and put ffmpeg.exe in this folder.
    exit /b 1
)

echo Installed tools\ffmpeg.exe
