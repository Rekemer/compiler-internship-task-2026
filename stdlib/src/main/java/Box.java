/*
to avoid these errors we need to wrap values in the immutable box

variable __loop1 might not have been initialized
local variables referenced from an inner class must be final or effectively final
local variables referenced from an inner class must be final or effectively fi

*/

public class Box<T>
{
    public T v;
    public Box(T v)
    {
        this.v=v;
    }
}