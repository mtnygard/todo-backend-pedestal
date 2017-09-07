# todo-backend-pedestal

An implementation of the [Todo Backend](http://www.todobackend.com/)
spec using Pedestal and Datomic.

## One-time Setup

1. Clone https://github.com/TodoBackend/todo-backend-js-spec.git
locally
2. [Run a Datomic transactor](http://docs.datomic.com/run-transactor.html)
2. Install the Datomic schema: `lein run -m install-schema _datomic_uri_`

## See it work

1. Start the application: `lein run-dev` \*
2. Open
   [the specs](http://www.todobackend.com/specs/index.html?http://localhost:8080/)
   to see test results.
3. Enter [localhost:8080](http://localhost:8080/) as the base URL

\* `lein run-dev` automatically detects code changes. Alternatively, you can run in production mode
with `lein run`.

## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).

## How to get to Here

This project started from the pedestal-service leiningen template:

`lein new pedestal-service todo-backend-pedestal`

I updated project.clj to bump the Clojure dependency up to 1.7.0-RC2
(the current revision when I started this.) I also added Datomic Free
as a dependency. The start-transactor.sh script is something I carry
from one project to the next because I have a bad memory for options.

The next thing I did was start working on the Pedestal routes. I
wanted to get the first of the Todo Backend tests to pass. To get
going, I started a REPL:

`lein repl`

Then I connected to it from CIDER (I'm an Emacs user.) Once I had a
prompt, I started a dev server so I could edit routes and reload the
file:

```
todo-backend.server=> (def srv (run-dev))

Creating your [DEV] server...
#'todo-backend.server/srv
```

At that point, I could hit http://localhost:8080/ and see the
template's default "Hello, World!" page.

I like to have my plumbing in place, so the next thing I added is the
`insert-datomic` interceptor. That attaches a Datomic connection to
every request, along with the value of the DB that is current when the
request begins. It's really handy to have that on the request, so
every piece of logic uses a consistent basis.

Next, it's a matter of creating routes and translating
data. todo-backend-js-spec uses JSON for transport, so we have a bit
of conversion to do in the handlers.

For the most part, we can use Pedestal interceptors. We use
`body-params/body-params`. It modifies the request map by
deserializing JSON into ordinary Clojure maps, then attaching that as
:json-params on the request. From there, we can translate JSON maps
into datoms to update Datomic. On the way back to the client, we
replace the keys for Datomic attributes with keywords. The
`bootstrap/json-body` interceptor serializes the data structure into
JSON as the response is generated.

## Links
* [Other examples](https://github.com/pedestal/samples)
