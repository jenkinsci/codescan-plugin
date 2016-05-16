package com.villagechief.codescan.jenkins;

import java.io.IOException;

public class CodeScanException extends RuntimeException {
	public CodeScanException(String message) {
	    super(message);
    }

	public CodeScanException(String message, Throwable exception) {
	    super(message, exception);
    }
}
