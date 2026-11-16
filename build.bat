@echo off
rem PocketMC PC - tek dosya .exe uretir (Python 3.9+ gerekli)
python -m pip install --upgrade pyinstaller || goto :err
python -m PyInstaller --onefile --noconsole --name PocketMC pocketmc_pc.py || goto :err
echo.
echo Bitti: dist\PocketMC.exe
pause
exit /b 0
:err
echo HATA olustu.
pause
