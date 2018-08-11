/*
 * =================================================
 * Copyright 2017 tagtraum industries incorporated
 * All rights reserved.
 * =================================================
 */
package com.github.jlmelville.abzexport;

import com.tagtraum.audiokern.AudioId;
import com.tagtraum.audiokern.AudioMetaData;
import com.tagtraum.audiokern.AudioSong;
import com.tagtraum.audiokern.StandardAudioId;
import com.tagtraum.beatunes.BeaTunes;
import com.tagtraum.beatunes.analysis.AudioAnalysisTask;
import com.tagtraum.beatunes.analysis.Task;
import com.tagtraum.beatunes.messages.Message;
import com.tagtraum.beatunes.onlinedb.OnlineDB;
import com.tagtraum.core.FileUtilities;
import com.tagtraum.core.OperatingSystem;
import com.tagtraum.core.ProgressListener;
import org.jetbrains.annotations.NotNull;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Entity;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.file.attribute.PosixFilePermission.*;

/**
 * AcousticBrainzSubmit.
 *
 * @author James Melville (based on code by Hendrik Schreiber)
 */
@Entity
public class AcousticBrainzExport extends AudioAnalysisTask {

    private static final Logger LOG = LoggerFactory.getLogger(AcousticBrainzExport.class);
    // SHA-1 to be used on submission profile
    private static final String ESSENTIA_BUILD_SHA = OperatingSystem.isMac() ? "cead25079874084f62182a551b7393616cd33d87" : "2d9f1f26377add8aeb1075a9c2973f962c4f09fd";
    private static final String STREAMING_EXTRACTOR_MUSIC = "streaming_extractor_music" + (OperatingSystem.isMac() ? "" : ".exe");
    private static final String PROFILE_YAML = "profile.yaml";
    private static final int OK = 0;
    private static Path executable;
    private static boolean hookRegistered;

    private static boolean headerWritten = false;

    static {
        try {
            executable = extractBinary();
        } catch (Exception e) {
            LOG.error("sorry couldn't extract binary: " + e.toString(), e);
        }
        if (executable == null) {
            LOG.error("sorry couldn't extract binary, executable is null");
        } else {
            LOG.error("executable is " + executable.toString());
        }
    }

    public AcousticBrainzExport() {
        setProgressRelevant(true);
    }

    private static Path extractBinary() throws IOException {
        if (LOG.isDebugEnabled()) LOG.debug("Extracting AcousticBrainz binaries...");
        try (final InputStream in = AcousticBrainzExport.class.getResourceAsStream(STREAMING_EXTRACTOR_MUSIC)) {
            final Path dir = Files.createTempDirectory("abzexport");
            if (LOG.isDebugEnabled()) LOG.debug("Executable directory: " + dir);
            final Path executable = dir.resolve(STREAMING_EXTRACTOR_MUSIC);
            Files.copy(in, executable);
            try {
                // actually make executable
                final Set<PosixFilePermission> permissions = new HashSet<>(Arrays.asList(
                        OWNER_READ, OWNER_WRITE, OWNER_EXECUTE,
                        GROUP_READ, GROUP_EXECUTE,
                        OTHERS_READ, OTHERS_EXECUTE
                ));
                Files.setPosixFilePermissions(executable, permissions);
            } catch (UnsupportedOperationException e) {
                LOG.warn("Was not able to make executable. Operation not supported on this platform.");
            }
            // create profile, see https://github.com/MTG/acousticbrainz-client/blob/master/abz/config.py#L60-L65
            try (final BufferedWriter writer = Files.newBufferedWriter(dir.resolve(PROFILE_YAML))) {
                writer.write("requireMbid: false\n" +
                        "indent: 0\n" +
                        "mergeValues:\n" +
                        "    metadata:\n" +
                        "        version:\n" +
                        "            essentia_build_sha: " + ESSENTIA_BUILD_SHA + "\n");
            }

            return executable.toAbsolutePath();
        }
    }

    private static Map<String, String> flatten(JSONObject nested) {
        Map<String, String> flat = new HashMap<>();
        flatten(flat, nested, "");
        return flat;
    }

    private static void flatten(Map<String, String> flat, JSONObject nested, String prefix) {
        Iterator it = nested.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry) it.next();
            String key = (String) pair.getKey();
            Object object = pair.getValue();
            String objPrefix = key;
            if (prefix.length() > 0) {
                objPrefix = prefix + ":" + key;
            }
            if (object instanceof JSONObject) {
                flatten(flat, (JSONObject) object, objPrefix);
            } else if (object instanceof JSONArray) {
                flatten(flat, (JSONArray) object, objPrefix);
            } else {
                flat.put(objPrefix, object.toString());
            }
        }
    }

    private static void flatten(Map<String, String> flat, JSONArray nested, String prefix) {
        for (int i = 0; i < nested.size(); i++) {
            String arrPrefix = prefix + "[" + i + "]";
            Object arrObj = nested.get(i);
            if (arrObj instanceof JSONObject) {
                flatten(flat, (JSONObject) arrObj, arrPrefix);
            } else if (arrObj instanceof JSONArray) {
                flatten(flat, (JSONArray) arrObj, arrPrefix);
            } else {
                flat.put(arrPrefix, arrObj.toString());
            }
        }
    }

    public String getName() {
        return "<html>AcousticBrainz<br>Export</html>";
    }

    public String getDescription() {
        return "<h1>AcousticBrainz Export</h1><p>Lets you execute the <a href=\"https://acousticbrainz.org\">AcousticBrainz</a> " +
                "feature extractor on your music files and exports the data locally.</p>";
    }

    @Override
    public void setApplication(final BeaTunes beaTunes) {
        super.setApplication(beaTunes);
        registerShutdownHook();
    }

    private void registerShutdownHook() {
        synchronized (AcousticBrainzExport.class) {
            if (!hookRegistered) {
                getApplication().addShutdownHook(() -> {
                    final Path executableParent = executable.getParent();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Deleting temporary AcousticBrainz binaries from " + executableParent);
                    try {
                        FileUtilities.deleteRecursively(executableParent);
                    } catch (Exception e) {
                        LOG.error("Failure while deleting temporary AcousticBrainz binaries " + executableParent, e);
                    }

                    return true;
                });
                hookRegistered = true;
            }
        }
    }

    @Override
    public void runBefore(final Task task) {
        final AudioSong song = getSong();
        if (song != null && song.getFile() != null) {
            final List<Path> filesToDelete = new ArrayList<>();
            try {
                final String mbid = getMBID(song);
                process(song, mbid, filesToDelete);
            } catch (Exception e) {
                LOG.error(e.toString(), e);
                getMessagePanel().addMessage(new Message(
                        getApplication().localize("Analysis"),
                        "Failed to export AcousticBrainz data for '" + song.getName() + "': " + e,
                        song.getId()
                ));
            } finally {
                getAnalysisProgress().getOperationProgressListener().progress(1f);

                // cleanup
                filesToDelete.forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        LOG.error(e.toString(), e);
                    }
                });
            }
        } else {
            String songName = "Unknown Song Name";
            Long songId = 0L;
            if (song != null) {
                if (song.getName() != null) {
                    songName = song.getName();
                }
                if (song.getId() != null) {
                    songId = song.getId();
                }
            }
            getMessagePanel().addMessage(new Message(
                    getApplication().localize("Analysis"),
                    "Failed to submit '" + songName + "' to AcousticBrainz. File not found.",
                    songId
            ));
        }
    }

    private void process(final AudioSong song,
                         final String mbid,
                         final List<Path> deleteList) throws IOException, UnsupportedAudioFileException, InterruptedException {
        final ProgressListener progressListener = getAnalysisProgress().getOperationProgressListener();
        progressListener.progress(0.25f);
        final List<String> allMBIDs = getMBIDs(song);
        if (allMBIDs.size() > 1) {
            LOG.warn("Track " + song.getName() + ". Found multiple MBIDs: " + allMBIDs);
        } else {
            if (mbid != null) {
                if (LOG.isDebugEnabled()) LOG.debug("Track " + song.getName() + ". Found MBID " + mbid);
            }
        }
        // AudioMetaData is the direct access to the file, without going through
        // any indirection like the beaTunes internal database
        final List<String> embeddedMBID = getMBIDs(song.getImplementation(AudioMetaData.class));
        final Path inputFile;
        if (embeddedMBID.isEmpty() && mbid != null) {
            if (LOG.isInfoEnabled())
                LOG.info("Track " + song.getName() + ". MBID is not embedded. Embedding " + mbid + " into copy. Consider embedding MBIDs before running this task.");
            inputFile = createCopyWithMBID(song, mbid);
            deleteList.add(inputFile);
        } else {
            inputFile = song.getFile().toAbsolutePath();
        }
        progressListener.progress(0.4f);
        if (executable == null) {
            LOG.error("Sorry, exe is null");
        }
        Path parent = executable.getParent();
        if (parent == null) {
            LOG.error("Sorry, exe parent is null");
        }
        final Path outputFileRel = Files.createTempFile(parent, "acousticbrainz", ".json");
        if (outputFileRel == null) {
            LOG.error("Sorry rel output temp file is null");
        }
        final Path outputFile = outputFileRel.toAbsolutePath();
        if (outputFile == null) {
            LOG.error("Sorry output temp file is null");
        }

        deleteList.add(outputFile);
        final Process process = executeStreamingExtractorMusic(inputFile, outputFile);
        if (LOG.isDebugEnabled()) LOG.debug("Output: " + getOutput(process));
        final int exitCode = process.waitFor();
        progressListener.progress(0.5f);
        if (exitCode == OK) {
            postToAcousticBrainz(song, outputFile);
        } else {
            getMessagePanel().addMessage(new Message(
                    getApplication().localize("Analysis"),
                    "Failed to submit '" + song.getName() + "' to AcousticBrainz. Exit code " + exitCode + ". See log for details.",
                    song.getId()
            ));
        }
    }

    private List<String> getMBIDs(final AudioSong song) {
        return song.getTrackIds()
                .stream()
                .filter(id -> AudioId.MUSIC_BRAINZ_TRACK.equals(id.getGeneratorName()))
                .map(AudioId::getId).collect(Collectors.toList());
    }

    @NotNull
    private Path createCopyWithMBID(final AudioSong song, final String mbid) throws IOException, UnsupportedAudioFileException {
        final Path inputFile;// at this point, we don't want to manipulate the original file...
        inputFile = Files.createTempFile("copy", FileUtilities.getExtension(song.getFile()));
        Files.copy(song.getFile(), inputFile, StandardCopyOption.REPLACE_EXISTING);
        // embed mbid into the copy
        AudioMetaData.get(inputFile).getTrackIds().add(new StandardAudioId(AudioId.MUSIC_BRAINZ_TRACK, mbid));
        return inputFile;
    }

    /**
     * Extract MBID from JSON output, if available.
     *
     * @param mbid       MBID we found in our local database
     * @param outputFile JSON file produced by the AcousticBrainz extractor
     * @return MBID that matched the JSON file
     * @throws IOException    if the output file cannot be opened or a problem occurs during JSON parsing.
     * @throws ParseException if the contents of the output file cannot be parsed as JSON.
     */
    private String extractMBID(final String mbid, final Path outputFile) throws IOException, ParseException {
        final String usedMBID;
        try (final BufferedReader in = Files.newBufferedReader(outputFile)) {
            final JSONObject json = (JSONObject) new JSONParser().parse(in);
            final JSONObject metadata = (JSONObject) json.get("metadata");
            final JSONObject tags = (JSONObject) metadata.get("tags");
            final JSONArray extractedMBIDs = (JSONArray) tags.get("musicbrainz_trackid");
            final String extractedMBID = extractedMBIDs != null && !extractedMBIDs.isEmpty() ? (String) extractedMBIDs.get(0) : null;
            if (extractedMBID != null && !extractedMBID.equals(mbid)) {
                if (LOG.isInfoEnabled()) LOG.info("Replaced originally found MBID " + mbid + " with " + extractedMBID);
                usedMBID = extractedMBID;
            } else {
                usedMBID = mbid;
            }
        }
        return usedMBID;
    }

    @NotNull
    private String getOutput(final Process process) throws IOException {
        final InputStream inputStream = process.getInputStream();
        final StringBuilder sb = new StringBuilder();
        final byte[] buf = new byte[256];
        int count;
        while ((count = inputStream.read(buf)) != -1) {
            sb.append(new String(buf, 0, count, StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }

    @NotNull
    private Process executeStreamingExtractorMusic(final Path inputFile, final Path outputFile) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder(
                executable.toString(),
                inputFile.toString(),
                outputFile.toString(),
                executable.resolveSibling(PROFILE_YAML).toString());
        builder.redirectErrorStream(true);
        builder.directory(executable.getParent().toFile());
        return builder.start();
    }

    private void postToAcousticBrainz(final AudioSong song, final Path file) throws IOException {
        Path userHome = Paths.get(System.getProperty("user.home"));
        Path outputFile = userHome.resolve("out.tsv");
        // If the file already exists, we're going to append, so don't add the header
        headerWritten = Files.exists(outputFile);
        PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputFile, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND));
        try (final BufferedReader in = Files.newBufferedReader(file)) {
            final JSONObject json = (JSONObject) new JSONParser().parse(in);
            json.remove("metadata");
            ((JSONObject) json.get("rhythm")).remove("beats_position");

            Map<String, String> flat = flatten(json);
            List<String> keys = new ArrayList<>(flat.keySet());
            Collections.sort(keys);

            if (!headerWritten) {
                StringJoiner joiner = new StringJoiner("\t").add("Name").add("Artist").add("Album");
                for (String key : keys) {
                    joiner.add(key);
                }
                writer.println(joiner.toString());
                headerWritten = true;
            }

            StringJoiner joiner = new StringJoiner("\t");
            joiner.add(song.getName()).add(song.getArtist()).add(song.getAlbum());

            for (String key : keys) {
                joiner.add(flat.get(key));
            }
            writer.println(joiner.toString());
        } catch (Exception e) {
            LOG.error(e.toString(), e);
        } finally {
            writer.close();
        }
    }

    /**
     * Extract MBID from {@link AudioSong} object and if we cannot find it,
     * attempt to look it up in the central database.
     *
     * @param song song
     * @return MBID or null
     */
    private String getMBID(final AudioSong song) {
        return song.getTrackIds()
                .stream()
                .filter(id -> AudioId.MUSIC_BRAINZ_TRACK.equals(id.getGeneratorName()))
                .map(AudioId::getId)
                .findFirst().orElseGet(() -> {
                    // there is no MBID embedded, let's look one up
                    final OnlineDB onlineDB = getApplication().getPluginManager().getImplementation(OnlineDB.class);
                    try {
                        return onlineDB.lookup(song)
                                .stream()
                                .flatMap(s -> s.getTrackIds().stream())
                                .filter(id -> AudioId.MUSIC_BRAINZ_TRACK.equals(id.getGeneratorName()))
                                .map(AudioId::getId)
                                .findFirst().orElse(null);
                    } catch (Exception e) {
                        LOG.error("Failed to look up MBID via OnlineDB.", e);
                    }
                    return null;
                });
    }
}