# DiscPlayer (Paper / Leaf 26.1.2, Java 25)

- `/discplayer give [player]` gives the **portable player**. Right click (in the air or on a block) opens the menu.
- **Shift + right click** on a jukebox opens the same menu for the placed jukebox.
- Menu (2 chest rows): 5 disc slots on the top row, below them **Start** (green), **Stop** (red) and a status icon.
- Discs play in slot order, forever: a finished disc goes to the end of the queue. Music only starts with **Start**;
  **Stop** switches it off, the next **Start** begins from the first disc.

## CustomDiscs support

Discs made with the [CustomDiscs](https://modrinth.com/plugin/customdiscs) plugin (2.7.0 tested by reading its jar, not in-game)
can be put into the menu together with normal discs. DiscPlayer uses CustomDiscs' public API through reflection, so it
still works without CustomDiscs (then only vanilla discs are accepted).

- **Jukebox:** a custom disc is put into the jukebox and CustomDiscs streams it through Simple Voice Chat, exactly as if
  you had inserted it by hand. The queue moves on when the stream ends.
- **Portable player:** the stream is started at the owner's position and its voice-chat channel is moved with the owner
  every 4 ticks. If the owner changes world, the current disc restarts.
- If a custom disc stops right after the start (missing file, voice chat not running...), the player/jukebox is switched
  off instead of looping, and a message is shown / logged.

Build: push to GitHub -> Actions -> Build -> download the `DiscPlayer` artifact (jar) into `plugins/`.
