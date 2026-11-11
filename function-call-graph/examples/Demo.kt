package examples

fun leaf(value: Int): Int = value + 1

fun process(value: Int): Int = leaf(value) + leaf(value + 1)

fun start() {
    process(1)
    process(2)
    val reference = ::process
    val callback = { process(3) }
    Demo.process(4)
}

fun recursive(value: Int): Int = if (value <= 0) 0 else recursive(value - 1)

val initialized = process(4)
