# abzexport

A [Beatunes](https://www.beatunes.com) plugin to export [AcousticBrainz](http://acousticbrainz.org/) audio data to 
disk.

The AcousticBrainz software generates a large amount of juicy numerical data about your music collection which might
be fun or even useful to play with, if you are suitably inclined towards machine learning. The
[AcousticBrainzSubmit](https://github.com/beatunes/plugin-samples/tree/master/abzsubmit) plugin submits this
data to the AcousticBrainz server, but does not keep it around for local use. This plugin writes the data
to a tab-separated file in a format which is straightforward to read in the like of R or Pandas for further
analysis and visualization.

## Status

*August 11 2018*. This should be functional, but it's incredibly crude (you can't choose what to export, the location
of the output file or even what the filename should be called).

## Building

I use [Gradle](https://gradle.org) to build the plugin, because I don't have much experience with
[Maven](https://maven.apache.org). This required a trivial change to `plugin.xml` (see the
[keytocomment-gradle](https://github.com/jlmelville/keytocomment-gradle) repo for more details).

Windows:
```Batchfile
gradlew.bat build
```

Linux:
```Shell
./gradlew build
```

You can find the built JAR file as `build/libs/abzexport-<version>.jar`.

## Deploying the plugin

Copy the `abzexport-<version>.jar` file into your `plugins` directory. On my Windows 10 installation, it's in the user's 
`AppData\Local\tagtraum industries\beaTunes\plugins` directory, rather than where Beatunes itself is installed.
If you can find a directory containing the `sameartistdifferentgrouping-1.0.2.jar` file, that's where it should go.

If the plugin was installed correctly, inside Beatunes, go to Edit > Preferences > Plugins, 
and on the Installed tab should be a plugin called 'AcousticBrainz Export'.

## Running

When you Analyze songs, there should now be a new option when the Analysis Options window opens, called 'AcousticBrainz
Export'. It has no adjustable parameters. You can just select it and let it go.

## Output

The output of the analysis is a file called `out.tsv` that will be created in your home directory. If the file already
exists, it appends to it. It's a tab-separated file that contains the fixed-length numerical data created for 
AcousticBrainz, along with the name of each song, the artist, and the album. 

The first line is a header giving the names of the features being exported. The `metadata` and `beats_position` data
are not kept because these are variable length.

Here's how to import the data into R and Python:

```R
# base reader
music_data <- read.delim("/path/to/out.tsv")
# you might prefer to install the readr package
library(readr)
music_data <- read_delim("/path/to/out.tsv", 
    "\t", escape_double = FALSE, trim_ws = TRUE)
```

```python
import pandas as pd
music_data = pd.read_csv("/path/to/out.tsv", sep='\t')
```

You should be able to import these into spreadsheets too, except that there are a large number of columns
(> 2,500) which may exceed the maximum number of columns for some software.

## Limitations

Here are some things that you *ought* to be able to do, but can't:

* Change the location and name of the output file.
* Change the separator.
* Select the data to be exported.

I would welcome any help, pull requests and so on to make this happen.

## License

[AGPL-3](https://www.gnu.org/licenses/agpl-3.0.txt), in keeping with that of  
[AcousticBrainzSubmit](https://github.com/beatunes/plugin-samples/tree/master/abzsubmit). Note that the repo README 
says that it's [CC0](https://creativecommons.org/share-your-work/public-domain/cc0/), but the plugin information 
displayed inside Beatunes says it's AGPL-3, so I've gone with the more restrictive license.
