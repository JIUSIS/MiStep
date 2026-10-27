package com.jianguo.mistep;

import java.text.Normalizer;
import java.util.Locale;

/** Reject another recording before accepting any public-catalog lyric. */
final class MediaLyricMatch {
    static String normalized(String value) {
        if(value==null)return "";
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
    }
    static boolean matches(String title,String artist,long duration,String candidateTitle,String candidateArtist,long candidateDuration) {
        String name=normalized(title);
        if(name.isEmpty()||!name.equals(normalized(candidateTitle)))return false;
        String by=normalized(artist),other=normalized(candidateArtist);
        if(by.isEmpty()||other.isEmpty())return false;
        boolean same=by.equals(other);
        if(!same)for(String a:artist.split("(?i)(?:[/、,，;；&]|\\s+(?:feat\\.?|featuring|x|×)\\s+)"))
            for(String b:candidateArtist.split("(?i)(?:[/、,，;；&]|\\s+(?:feat\\.?|featuring|x|×)\\s+)")) {
                String part=normalized(a);
                if(part.length()>=2&&part.equals(normalized(b)))same=true;
            }
        if(!same)return false;
        // A missing duration cannot distinguish covers, live versions and clipped audio.
        if(duration<=0||candidateDuration<=0)return false;
        long tolerance=Math.max(2000,Math.min(5000,duration/50));
        return Math.abs(duration-candidateDuration)<=tolerance;
    }
}
