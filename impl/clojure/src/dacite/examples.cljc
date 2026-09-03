(ns dacite.examples
  "Claim-proving apps compiled and tested with the library.

   Domain code uses only `dacite.value` and `dacite.store`. Store wiring
   is `s/mem`, `s/file`, `s/lmdb`, or `s/remote`. Tests live in
   `test/dacite/examples/`.

   | Namespace                    | Pattern                          |
   |------------------------------|----------------------------------|
   | dacite.examples.hello        | constructors + content hash      |
   | dacite.examples.parity       | cross-host hash identity         |
   | dacite.examples.config       | nested document, file or HTTP    |
   | dacite.examples.notes        | history is free                  |
   | dacite.examples.event-log    | large seq, page, two-writer CAS  |
   | dacite.examples.sync         | tree of blobs                    |
   | dacite.examples.todo         | Values / Store split             |
   | dacite.examples.explorer     | walk without dumping             |
   | dacite.examples.cards        | durable LMDB game                |")
