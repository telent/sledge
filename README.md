# Sledge - We're lost in music

Available protocols such as DAAP, DLNA etc for publishing music media
files (e.g. ripped CDs) on the local network and/or the internet are
either insanely complex or proprietary or sometimes both.  Which is odd given that HTTP is so eminently suited to schlepping bits across the net.

Sledge is a spare-time/weekend hack to see if there's a simpler way.
It runs [green-tags](https://github.com/DanPallas/green-tags) on your audio collection and creates an index on disk, then starts a web server that knows how to transcode it (requires [libav](https://libav.org/)) and provides a single-page JS (actually ClojureScript) app that lets you search it and play the music.

Status: works on my machine, works on my phone (Firefox for Android), ugly, many rough edges.

## Prerequsites

You need [ffmpeg](https://ffmpeg.org/) and it needs to be a build that
supports playing all the formats of music that you intend to play.


## Configuring

First create a configuration file to tell it where your music collection is and where to create its index files

```
$ cat sledge.conf.edn
{:index "/home/dan/.sledge/"
 :folders ["/home/dan/Music/"]
 :port 53281
}
```

## Running in deployment mode

    $ boot build
    $ AVCONV=`type -p ffmpeg` java -jar target/project.jar sledge.conf.edn

Now point your web browser at http://localhost:53281


## Running in development mode (terminal-based)

There are probably as many ways to do this as there are people who
want to.

I run three windows to (a) start an nrepl server, (b) rebuild cljs
files, (c) run a cljs repl

```
window1$ boot pig repl
window2$ boot watch cljs -O whitespace target -d dev-target
window3$ boot repl -c -e '(wait-for-browser-repl)'
```

Next you need to start the server: in the window running the CLJ repl,
run

```
(require 'sledge.core :reload)
(sledge.core/-main "sledge.conf.edn")
```

Now go to http://localhost:53281/, open the browser console, and
evaluate `window.repl()` 

And now go to the window running `wait-for-browser-repl`, where you
should see something like this:

```
<< started Weasel server on ws://0.0.0.0:9001 >>
<< waiting for client to connect ...  connected! >>
To quit, type: :cljs/quit
cljs.user=> 
```

Adding CIDER to this is left as an exercise for the reader.


## Securing it

Don't run this on an untrusted network.  It's had no real security
review and it runs external commands.

I started looking at what it would take to add SSL, but it's kind of
involved, and unlikely to be as secure as running a separate HTTPS
proxy which has been audited by someone who know what they're doing.
If you're running a Linux-like OS, you can use stud as an SSL proxy:
setup would be something like this -

```
$ openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out
cert.pem -days 365
$ cat *.pem > ssl.combined
$ sudo stud -f 0,443 -b 0,53281 ssl.combined
```


## A note on Android

The builtin browser in Android Jelly Bean, at least on the phone I
own, doesn't work very well with Sledge once the screen turns off.
The browser stops calling JS event handlers, meaning that when the
player reaches the end of the current track Sledge isn't told and
can't start the next one.  I can't see an easy way to fix it either,
but a simple workaround is to download and use Firefox for Android
instead.


## Copyright

Copyright © 2014-2016 Daniel Barlow

Distributed under the GNU Affero General Public License, which means
this is free software but that if you run it for the benefit of people
who are not you, you need to provide them a link to download what
you're running.

Why the restriction?  It depends on libav for transcoding, and libav is GPL, and Affero GPL seems to be a good option for enforcing the spirit of GPL for systems where the software itself lives on a server and is not actually distibuted to its users. 

If that poses a problem for your preferred use case, I am happy to
discuss alternative licencing arrangements.
