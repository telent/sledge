all: sledge.jar
	docker build -t winff .
	docker run -v `pwd`/out:/mnt winff cp -vr /tmp/sledge.msi /mnt
	find out

sledge.jar: ../target/project.jar
	cp $< $@
