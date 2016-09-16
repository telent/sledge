with import <nixpkgs> {}; 
let sourceFilesOnly = path: type:
    (lib.hasPrefix "var" (toString path)) ||
    (lib.hasPrefix "m2repo" (toString path)) ||
    (lib.hasPrefix "target" (toString path)) ;
in stdenv.mkDerivation {
    name = "sledge";
    src = builtins.filterSource sourceFilesOnly ./.;
    buildInputs = [ boot openjdk nodejs ffmpeg ];
    M2REPOSITORY = ''m2repo'';
    AVCONV = "${ffmpeg.bin}/bin/ffmpeg";
    buildPhase = ''
      boot build
    '';
}

