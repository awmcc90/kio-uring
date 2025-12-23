package example

import example.runners.TransactionalJournal

fun main(args : Array<String>) {
    TransactionalJournal.run().get()
}
