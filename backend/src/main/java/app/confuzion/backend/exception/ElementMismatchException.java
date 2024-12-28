/**
 * MIT License
 * <p>
 * Copyright (c) 2024 McAwful-Works
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package app.confuzion.backend.exception;

import lombok.Generated;

import java.io.Serial;

/**
 * Exception to handle when there is an element mismatch in an argument, such as
 * when an element is given when it already exists or when an element is given
 * and should exist but doesn't.
 *
 * @author mcawful
 */
@Generated
public class ElementMismatchException extends IllegalArgumentException {
    @Serial
    private static final long serialVersionUID = 4646116550522682401L;

    /**
     * The default exception message.
     */
    private static final String DEFAULT_MSG = "Element mismatch occurred";

    /**
     * Constructs a {@code ElementMismatchException} with the default detail message.
     */
    public ElementMismatchException() {
        super(DEFAULT_MSG);
    }

    /**
     * Constructs a {@code ElementMismatchException} with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public ElementMismatchException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a {@code ElementMismatchException} with the specified detail message.
     *
     * @param message the detail message
     */
    public ElementMismatchException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code ElementMismatchException} with the default detail message and cause.
     *
     * @param cause the cause
     */
    public ElementMismatchException(Throwable cause) {
        super(DEFAULT_MSG, cause);
    }
}
