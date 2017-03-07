all:
	docker build -t winff .
	docker run -v `pwd`/out:/mnt winff cp -vr /tmp/sledge.msi /mnt
	find out
