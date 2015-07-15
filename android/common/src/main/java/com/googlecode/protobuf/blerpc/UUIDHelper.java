package com.googlecode.protobuf.blerpc;

import java.text.MessageFormat;

public class UUIDHelper {
	
	public static String expandUUID(String briefUUID) {
        StringBuilder sb = new StringBuilder(briefUUID);
        while (sb.length() < 8)
            sb.insert(0, "0");
        return MessageFormat.format("{0}-0000-1000-8000-00805F9B34FB", sb);
    }

}