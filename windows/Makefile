
default: cdrom.iso

out/sledge.msi: sledge.jar sledge.bat
	docker build -t winff .
	docker run -v `pwd`/out:/mnt winff cp -vr /tmp/sledge.msi /mnt

music.zip:
	zip $@ ~/Music/

cdrom.iso: out/sledge.msi autorun.inf hello.bat
	if  test -d var/cdrom ; then rm -r var/cdrom ;fi
	mkdir var/cdrom
	( cd $(HOME) && find Music/ -type f -print0 |shuf -z |head -z -n 10 |xargs -0 -n1 echo  | cpio -o ) | (cd var/cdrom && cpio -i --make-directories )
	cp $^ var/cdrom
	genisoimage var/cdrom > $$$$ && mv $$$$ $@

sledge.jar: ../target/project.jar
	cp $< $@
