FROM debian:stretch
RUN apt-get -y update
RUN apt-get -y install build-essential msitools mingw-w64 git subversion texinfo  subversion curl texinfo g++ bison flex cvs yasm automake libtool autoconf gcc cmake git make pkg-config zlib1g-dev mercurial unzip pax nasm libtool-bin ed  wget nsis wget
RUN mkdir /src
WORKDIR /src
RUN mkdir /target
RUN git clone --depth 1 https://github.com/FFmpeg/FFmpeg.git /src/ffmpeg
WORKDIR /src/ffmpeg
RUN PKG_CONFIG_PATH=/target/lib/pkgconfig \
	./configure --enable-gpl --enable-static --disable-shared \
	  --arch=x86 --target-os=mingw32 \
          --prefix=/target \
	  --cross-prefix=i686-w64-mingw32- --pkg-config=pkg-config 
RUN make install 

WORKDIR /src/
RUN wget https://www.openssl.org/source/openssl-1.0.2k.tar.gz 
RUN tar zxf openssl-1.0.2k.tar.gz
WORKDIR /src/openssl-1.0.2k
RUN ./Configure no-shared --prefix=/target --openssldir=/target --cross-compile-prefix=i686-w64-mingw32- no-ssl2 no-weak-ssl-ciphers  mingw
RUN make depend all install
RUN cp ms/applink.c /target/include/openssl

WORKDIR /src/
RUN wget https://www.stunnel.org/downloads/stunnel-5.40.tar.gz 
RUN tar zxf stunnel-5.40.tar.gz
WORKDIR /src/stunnel-5.40
RUN ./configure --with-ssl=/target --prefix=/target
RUN mkdir src/openssl
RUN sed -i.dist -e 's/^win32_ssl_dir =.*$/win32_ssl_dir = \/target/' \
  -e 's/^win32_ssl_libs =.*$/win32_ssl_libs = -L\/target\/lib  -lssl -lcrypto -lcrypt32 -lgdi32/' \
  -e 's/^win32_gui_libs =.*$/win32_gui_libs = -lws2_32 -lkernel32 -L\/target\/lib  -lssl -lcrypto -lcrypt32 -lgdi32 -lpsapi  /' \
  src/mingw.mk 
RUN make -C src mingw
RUN cp bin/mingw/*.exe /target/bin

RUN mkdir /dist
WORKDIR /src
RUN wget http://cdn.azul.com/zulu/bin/zulu8.20.0.5-jdk8.0.121-win_x64.zip 
RUN unzip zulu8.20.0.5-jdk8.0.121-win_x64.zip  zulu8.20.0.5-jdk8.0.121-win_x64/jre/*
RUN cp -r zulu8.20.0.5-jdk8.0.121-win_x64/jre /dist

RUN apt-get -y install wixl
COPY sledge.ico /tmp
COPY sledge.bat /dist/
COPY project.jar /dist/sledge.jar
RUN cp -r /target/bin /target/lib  /dist
RUN find /dist/bin/ -type f | wixl-heat -p /dist/ --directory-ref APPROOTDIR --var var.SourceDir --component-group CG.generated > /tmp/files.wxs
COPY sledge.wxs /tmp
RUN wixl -D SourceDir=/dist/ -o /tmp/sledge.msi  /tmp/sledge.wxs /tmp/files.wxs 
