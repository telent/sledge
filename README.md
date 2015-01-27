# Sledge - We're lost in music

Available protocols such as DAAP, DLNA etc for publishing music media
files (e.g. ripped CDs) on the local network and/or the internet are
either insanely complex or proprietary or sometimes both.  Which is odd given that HTTP is so eminently suited to schlepping bits across the net.

Sledge is a spare-time/weekend hack to see if there's a simpler way.
It runs [green-tags](https://github.com/DanPallas/green-tags) on your audio collection and creates an index on disk, then starts a web server that knows how to transcode it (requires [libav](https://libav.org/)) and provides a single-page JS (actually ClojureScript) app that lets you search it and play the music.

Status: works on my machine, works on my phone (Firefox for Android), ugly, many rough edges.

## Configuring

First create a configuration file to tell it where your music collection is and where to create its index files

```
$ cat sledge.conf.edn
{:index "/srv/media/.sledge/"
 :folders ["/srv/media/Music/"]
 :port 53281
}
```

## Running in development mode

I have not as yet manage to figure out why the leiningen `dev` profile appears to be loaded even when making uberjars.  Until I do (and all advice on that score is welcome), it is necessary to use an additional profile to include dev-only configuration such as the browser repl

    $ lein with-profile +brepl cljsbuild auto
    # and in another window
    $ lein with-profile +brepl repl
    sledge.core=> (-main "conf.edn")

Now point your web browser at http://localhost:53281

A websocket-based browser repl is available, assuming your browser
supports websockets: run `(user/simple-brepl)` to start it.


## Running in deployment mode

    $ lein with-profile uberjar do clean, cljsbuild once, uberjar
    $ java -jar target/uberjar+uberjar/sledge-0.1.0-SNAPSHOT-standalone.jar sledge.conf.edn

Now point your web browser at http://localhost:53281


## Copyright

Copyright © 2014,2015 Daniel Barlow

Distributed under the GNU Affero General Public License, which means
this is free software but that if you run it for the benefit of people
who are not you, you need to provide them a link to download what
you're running.

Why the restriction?  It depends on libav for transcoding, and libav is GPL, and Affero GPL seems to be a good option for enforcing the spirit of GPL for systems where the software itself lives on a server and is not actually distibuted to its users. 

If that poses a problem for your preferred use case, I am happy to
discuss alternative licencing arrangements.
