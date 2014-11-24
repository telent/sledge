# Sledge - We're lost in music

Available protocols such as DAAP, DLNA etc for publishing music media
files (e.g. ripped CDs) on the local network and/or the internet are
either insanely complex or proprietary or sometimes both.  Which is odd given that HTTP is so eminently suited to schlepping bits across the net.

Sledge is a spare-time/weekend hack to see if there's a simpler way.
It uses [green-tags](https://github.com/DanPallas/green-tags) and [clucy](https://github.com/weavejester/clucy) to index your audio /linkmedia collection, embeds a web server that knows how to transcode it (requires [libav](https://libav.org/)) then provides a single-page JS (actually ClojureScript) app that lets you search it and play the music.

Status: definitely early days, but works on my machine.  More or less.

## Building

Requires Leinigen. Clone the git repo, then

    $ lein do deps, uberjar

## Usage

    $ java -jar sledge-0.1.0-standalone.jar 

And point your web browser at http://localhost:53281

## Copyright

Copyright © 2014 Daniel Barlow

Distributed under the GNU Affero General Public License, which means
this is free software but that if you run it for the benefit of people
who are not you, you need to provide them a link to download what
you're running.  

Why the restriction?  It depends on libav for transcoding, and libav is GPL, and Affero GPL seems to be a good option for enforcing the spirit of GPL for systems where the software itself lives on a server and is not actually distibuted to its users. 

If that poses a problem for your preferred use case, I am happy to
discuss alternative licencing arrangements.
