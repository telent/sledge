@echo off
set datadir=%LOCALAPPDATA%\sledge
if not exist %datadir% (
  mkdir %datadir%
  echo {:index "%datadir:\=/%" :folders ["%USERPROFILE:\=/%/Music"] :port 53281} > %datadir%\conf.edn
)
echo Using config file %datadir%\conf.edn
jre\bin\java -jar sledge.jar %datadir%\conf.edn
pause
