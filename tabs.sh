#!/usr/bin/env bash
if ! test -f .nrepl-port ; then
    konsole --workdir $PWD --new-tab -e nix-shell --run  "boot watch cljs -O whitespace target -d dev-target"
    konsole --workdir $PWD --new-tab -e nix-shell --run  "boot cider repl"
    while ! test -f .nrepl-port ; do echo -n '.' ; sleep 1;done
    konsole --workdir $PWD --new-tab -e nix-shell --run  "boot repl -c -e '(wait-for-browser-repl)'"
fi
