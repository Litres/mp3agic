package com.mpatric.mp3agic;

public class ID3v22Tag extends AbstractID3v2Tag {
	
	public static final String VERSION = "2.0";

	public ID3v22Tag(String preferredLang) {
		super(preferredLang);
		version = VERSION;
	}

	public ID3v22Tag(String preferredLang, byte[] buffer) throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
		super(preferredLang, buffer);
	}
	
	public ID3v22Tag(String preferredLang, byte[] buffer, boolean obseleteFormat) throws NoSuchTagException, UnsupportedTagException, InvalidDataException {
		super(preferredLang, buffer, obseleteFormat);
	}
	
	protected void unpackFlags(byte[] bytes) {
		unsynchronisation = BufferTools.checkBit(bytes[FLAGS_OFFSET], UNSYNCHRONISATION_BIT);
		compression = BufferTools.checkBit(bytes[FLAGS_OFFSET], COMPRESSION_BIT);
	}

	protected void packFlags(byte[] bytes, int offset) {
		bytes[offset + FLAGS_OFFSET] = BufferTools.setBit(bytes[offset + FLAGS_OFFSET], UNSYNCHRONISATION_BIT, unsynchronisation);
		bytes[offset + FLAGS_OFFSET] = BufferTools.setBit(bytes[offset + FLAGS_OFFSET], COMPRESSION_BIT, compression);
	}
}
