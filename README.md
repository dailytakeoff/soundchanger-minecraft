# Sound Changer

A client-side Fabric mod for Minecraft **26.3** that lets you make your own sound packs — in game or by
dropping files into a folder — using audio files in almost any format.

For any sound in the game you can:

- **Replace** it with your own file(s)
- **Add** your file(s) next to Minecraft's, so the game picks at random between theirs and yours
- **Mute** it

Several files on one sound play at random, like Minecraft's own variants. Subtitles and hearing range
are kept from the original sound.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for 26.3 and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Put `SoundChanger-<version>.jar` in your `mods` folder.
3. Optional, for formats other than OGG/WAV/AIFF/MP3/FLAC: install **ffmpeg**
   (Arch/CachyOS: `sudo pacman -S ffmpeg`, Debian/Ubuntu: `sudo apt install ffmpeg`,
   Windows: `winget install ffmpeg`). If you use Sharing Craft on Windows, its ffmpeg is found automatically.

## Making a pack in game

Press **J** (changeable under *Options → Controls → Key Binds → Sound Changer*), or use the settings
button in Mod Menu.

1. Type a name and press **Create**.
2. In the editor, search for a sound on the left (e.g. `creeper`, `step`, `door`, `music`).
   Double-click to hear it.
3. Press **+ Add file…** to pick an audio file, or drag files from your file manager onto the window.
4. Choose **Replace Minecraft's sound** or **Add to Minecraft's sound** with the mode button,
   or press **Mute**.
5. Use **▶ Original** and **▶ With packs** to compare. Changes apply instantly.

The pack list sets which packs are on and their order: when two packs change the same sound, the higher one wins.

## Making a pack by hand

Packs live in `.minecraft/soundpacks/`. Each folder is one pack:

```
soundpacks/
  My Pack/
    replace/                        your sounds INSTEAD of Minecraft's
      entity.creeper.primed.mp3     a file named after the sound
      block.stone.break/            or a folder of files (picked at random)
      entity.cow.ambient/           an empty folder here mutes that sound
    add/                            your sounds NEXT TO Minecraft's
      entity.chicken.ambient.wav
    files/                          files the in-game editor copied in
    pack.json                       what the in-game editor set up
```

Sound names are the ones `/playsound` uses, without `minecraft:`. For another mod's sounds write
`modid~sound.name` (e.g. `create~whistle.mp3`). Press **Reload** in the pack list (or F3+T) after
changing files.

`pack.json` can also be edited by hand; each file can have `volume`, `pitch`, `weight`, `stream` and
`mono` settings:

```json
{
  "sounds": {
    "minecraft:entity.creeper.primed": {
      "mode": "replace",
      "files": ["files/hiss.mp3", { "file": "files/hiss2.ogg", "volume": 0.8, "pitch": 1.2, "weight": 2 }]
    }
  }
}
```

## Formats

| Built in | Needs ffmpeg |
| --- | --- |
| OGG Vorbis, WAV (8/16/24/32-bit, float), AIFF, AU, MP3, FLAC (8/16/24-bit) | M4A/AAC, Opus, WMA, WebM, ALAC, video files, and anything else ffmpeg reads |

The format is detected from the file's contents, not its name. Sounds that come from a place in the
world are mixed down to mono (OpenAL can only position mono sounds); music and menu sounds keep their
stereo. Long files and music are streamed from disk instead of loaded into memory.

## Sharing

Zip the pack's folder and send it. Your friends unzip it into their `soundpacks` folder — they need
Sound Changer too, since it plays the files directly instead of going through Minecraft's resource packs.

## Building

```
./gradlew build
```

The jar ends up in `build/libs/`. `./gradlew runClientGameTest` starts the game, builds a test pack in
every format, checks that each one decodes and that replace/add/mute work, and saves screenshots of the
menus. GitHub Actions runs both on every push and puts the jar, logs and screenshots on the `ci-out` branch.

## License

MIT. Bundles [JLayer](https://github.com/umjammer/jlayer) (MP3) and [jFLAC](https://github.com/nguillaumin/jflac)
(FLAC), both LGPL, as separate jars inside the mod jar.
