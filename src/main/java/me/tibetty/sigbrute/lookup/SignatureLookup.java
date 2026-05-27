package me.tibetty.sigbrute.lookup;

import java.util.List;

/** Resolves a 4-byte selector to known text signatures (4byte.directory / Sourcify). */
public interface SignatureLookup {

    List<String> lookup(byte[] selector4);
}
