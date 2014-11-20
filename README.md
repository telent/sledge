# sledge

Available protocols such as DAAP, DLNA etc for publishing music media
files (e.g. ripped CDs) on the local network and/or the internet are
either insanely complex or prprietary or sometimes both,

## Building

Requires Leinigen. Clone the git repo, then

$ lein do deps, uberjar

## Usage

    $ java -jar sledge-0.1.0-standalone.jar 

And point your web browser at http://lcoalhost:53281

## Copyright

Copyright © 2014 Daniel Barlow

Distributed under the GNU Affero General Public License, which means
this is free software but that if you run it for the benefit of people
who are not you, you need to provide them a link to download what
you're running.  

If that poses a problem for your preferred use case, I am happy to
negotiate terms for alternative licencing arrangements.
