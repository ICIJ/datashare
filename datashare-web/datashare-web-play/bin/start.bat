set scriptdir=%~dp0
set classpath=%scriptdir%/conf;%scriptdir%/lib/*
java %* -cp "%classpath%" play.core.server.ProdServerStart %scriptdir%
