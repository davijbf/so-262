/**
 * An interface for buffers
 *
 * Figure 4.9
 *
 * @author Gagne, Galvin, Silberschatz
 * Operating System Concepts with Java - Sixth Edition
 * Copyright John Wiley & Sons - 2003.
 */


public interface Buffer
{
    /**
     * insert an item into the Buffer.
     * Note this may be either a blocking
     * or non-blocking operation.
     */
    public abstract void insert(Object item);

    /**
     * remove an item from the Buffer.
     * Note this may be either a blocking
     * or non-blocking operation.
     */
    public abstract Object remove();
}