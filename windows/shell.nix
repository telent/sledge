with import <nixpkgs> {}; 
let sourceFilesOnly = path: type:
    (lib.hasSuffix "qcow2" (toString path)) ||
    (lib.hasPrefix "out" (toString path)) ||
    (lib.hasPrefix "var" (toString path)) ;
    windowsDisk = stdenv.mkDerivation rec {
        name = "ms-windows-10-disk";
        src = fetchurl {
            url = "https://az792536.vo.msecnd.net/vms/VMBuild_20160802/VirtualBox/MSEdge/MSEdge.Win10_RS1.VirtualBox.zip" ;
            md5 = "467d8286cb8cbed90f0761c3566abdda";
        };
        buildInputs = [ unzip qemu ];
        phases = [ "installPhase" ];
        installPhase  = ''
          mkdir -p $out raw
          unzip -p $src \*.ova |tar --wildcards -Oxvf - \*.vmdk > raw/tmp.vmdk
          qemu-img convert  -O qcow2  raw/tmp.vmdk $out/windows10.qcow2
        '';
    };
 in stdenv.mkDerivation {
      name = "wsledge";
      buildInputs = [ docker kvm windowsDisk ];
      buildPhase = ''
        make
      '';
      shellHook = ''
        emu(){
          mkdir -p var ;
          qemu-img create -f qcow2 -b ${windowsDisk}/windows10.qcow2 var/scratch.qcow2 ; 
          ( qemu-kvm -usbdevice tablet -display sdl -m 4G -hda var/scratch.qcow2 & );
          ( cd out && python -m SimpleHTTPServer 8029  )
        }        
      '';
 }

