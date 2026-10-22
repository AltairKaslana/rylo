package com.example.ytmplayer;

/** Satu entri dari server: song | album | artist | playlist. */
public class Item {
    public String kind, id, title, subtitle, thumb;
    public int dur;

    public boolean isSong() {
        return "song".equals(kind);
    }
}
