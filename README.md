# FaunaDB Importer

FaunaDB Importer is a command line utility to help you import static data into
[FaunaDB](https://fauna.com/product). It can import data into FaunaDB Cloud or
an on-premises FaunaDB Enterprise cluster.

Supported input file formats:
- JSON
- CSV
- TSV

Requirements:
- Java 8+

## Usage

Download the [latest version](https://github.com/fauna/faunadb-importer/releases)
and extract the zip file. Inside the extracted folder, run:

```
./bin/faunadb-importer \
  import-file \
  --secret <keys-secret> \
  --class <class-name> \
  <file-to-import>
```

__NOTE__: The command line arguments are the same on Windows but, you must use a
different startup script. For example:

```
.\bin\faunadb-importer.bat import-file --secret <keys-secret> --class <class-name> <file-to-import>
```

For example:

```
./bin/faunadb-importer \
  import-file \
  --secret "abc" \
  --class users \
  data/users.json
```

The importer will load all data into the specified class, preserving the field
names and types as described in the import file.

You can also type `./bin/faunadb-importer --help` for more detailed information.

## How it works

The importer is a stateful process separated into two phases: ID generation
and data import.

First, the importer will parse all records and generate unique IDs by calling
the [`next_id`](https://fauna.com/documentation/queries#misc_functions-code_next_id_code)
function for each record. Pre-generating IDs beforehand allows us to import
schemas containing relational data while keeping foreign keys consistent. It
also ensures that we can safely re-run the process without the risk of
duplicating information.

In order to map legacy IDs to newly generated Fauna IDs, the importer will:
- Check if there is a field configured with the `ref` type. The field's value
  will be used as the lookup term for the new Fauna ID.
- If no field is configured with the`ref` type, the importer will assign a
  sequential number for each record as the lookup term for the new Fauna ID.

Once this phase completes, the pre-generated IDs will be stored at the `cache`
directory. In case of a re-run, the importer will load the IDs from disk and
skip this phase.

Second, the importer will insert all records into FaunaDB, using the
pre-generated IDs from the first step as their [ref](https://fauna.com/documentation/queries#values-special_types)
field.

At this phase, if the import fails to run due to data inconsistency, it is:
- __SAFE__ to fix data inconsistencies in any field **except** fields configured
  with the `ref` type.
- __NOT SAFE__ to change fields configured with the `ref` type as they will be
  used as the lookup term for the pre-generated ID from the first phase.
- __NOT SAFE__ to remove entries from the import file if you __don't have__ a
  field configured as a `ref` field; this will alter the sequential number
  assigned to the record.


As long as you keep the `cache` directory intact, it is safe to re-run the
process until the import completes. If you want to use the importer again with a
different input file, you __must__ empty the `cache` directory first.

### File structure

```
.
├── README.md                    # This file
├── bin                          #
│   ├── faunadb-importer         # Unix startup script
│   └── faunadb-importer.bat     # Windows startup script
├── cache                        # Where the importer saves its cache
├── data                         # Where you should copy the files you wish to import
├── lib                          #
│   └── faunadb-importer-1.0.jar # The importer library
└── logs                         # Logs for each execution
```

## Advanced usage

### Configuring fields

When importing `JSON` files, field names and types are optional; when
importing text files, you must specify each field's name and type in order using
the `--format` option:

```
./bin/faunadb-importer \
  import-file \
  --secret "<your-keys-secret-here>" \
  --class <your-class-name> \
  --format "<field-name>:<field-type>,..."
  <file-to-import>
```

For example:

```
./bin/faunadb-importer \
  import-file \
  --secret "abc" \
  --class users \
  --format "id:ref, username:string, vip:bool" \
  data/users.csv
```

#### Supported types:

|Name     | Description                      |
|---------|----------------------------------|
|`string` | A string value                   |
|`long`   | A numeric value                  |
|`double` | A double precision numeric value |
|`bool`   | A boolean value                  |
|`ref`    | A [ref](https://fauna.com/documentation/queries#values-special_types) value. It can be used to mark the field as a primary key or to reference another class when [importing multiple files](#importing-multiple-files). For example `city:ref(cities)`|
|`ts`     | A numeric value representing the number of milliseconds passed since 1970-01-01 00:00:00. You can also specify your own [format](http://www.joda.org/joda-time/key_format.html) as a parameter. For example: `ts("dd/MM/yyyyTHH:mm:ss.000Z")`|
|`date`   | A date value formatted as `yyyy-MM-dd`. You can also specify your own [format](http://www.joda.org/joda-time/key_format.html) as a parameter. For example: `date("dd/MM/yyyy")`|

### Renaming fields

You can rename fields from the input file as they are inserted into FaunaDB with
the following syntax:

```
<field-name>-><new-field-name>:<field-type>
```

For example:

```
./bin/faunadb-importer \
  import-file \
  --secret "abc" \
  --class users \
  --format "id:ref, username->userName:string, vip->VIP:bool" \
  data/users.csv
```

### Ignoring root element

When importing a `JSON` file where the root element of the file is an `array`, or
when importing a text file where the first line is the file header, you can skip
the root element with the `--skip-root` option. For example:

```
./bin/faunadb-importer \
  import-file \
  --secret "abc" \
  --class users \
  --skip-root true
  data/users.csv
```

### Ignoring fields

You can ignore fields with the `--ignore-fields` option. For example:

```
./bin/faunadb-importer \
  import-file \
  --secret "abc" \
  --class users \
  --format "id:ref, username->userName:string, vip->VIP:bool" \
  --ignore-fields "id"
  data/users.csv
```

_NOTE_: In the example above, we're configuring `id` as a `ref` type so it will
be used as the lookup term when resolving its Fauna ID, but we're configuring it
as an ignored field so we omit its value from the imported data.

### How to maintain data in chronological order

You can maintain chronological order when importing data by using the
`--ts-field` option. For example:

```
./bin/faunadb-importer \
  import-file \
  --secret "abc" \
  --class users \
  --ts-field "created_at"
  data/users.csv
```

The value configured in the `--ts-field` option will be used as the `ts`
field for the imported instance.

### Importing to your own cluster

By default, the importer will load your data into FaunaDB Cloud. If you wish to
import the data to your own cluster, you can use the `--endpoints` option. For
example:

```
./bin/faunadb-importer \
  import-file \
  --secret "abc" \
  --class users \
  --endpoints "http://10.0.0.120:8443, http://10.0.0.121:8443"
  data/users.csv
```

_NOTE_: The importer will load balance requests across all configured endpoints.

### Importing multiple files

In order to import multiple files, you must run the importer with a schema
definition file. For example:

```
./bin/faunadb-importer \
  import-schema \
  --secret "abc" \
  data/my-schema.yaml
```

#### Schema definition syntax

```yaml
<file-address>:
  class: <class-name>
  skipRoot: <boolean>
  tsField: <field-name>
  fields:
    - name: <field-name>
      type: <field-type>
      rename: <new-field-name>
  ignoredFields:
    - <field-name>
```

For example:

```yaml
data/users.json:
  class: users
  fields:
    - name: id
      type: ref

    - name: name
      type: string

  ignoredFields:
    - id

data/tweets.csv:
  class: tweets
  tsField: created_at
  fields:
    - name: id
      type: ref

    - name: user_id
      type: ref(users)
      rename: user_ref

    - name: text
      type: string
      rename: tweet

  ignoredFields:
    - id
    - created_at
```

## Performance considerations

The importer's default settings should be enough to provide good performance for
most cases. Still, there are a few things that are worth mentioning:

### Memory

You can set the maximum amount of memory available to the import tool with
`-J-Xmx`. For example:

```
./bin/faunadb-importer \
  -J-Xmx10G \
  import-schema \
  --secret "abc" \
  data/my-schema.yaml
```

_NOTE_: Parameters prefixed with `-J` must be placed as the first parameters for
the import tool.

### Batch sizes

The size of each individual batch is controlled by `--batch-size` parameter.

In general, individual requests will have a higher latency with a larger batch
size. However, the overall throughput of the import process may increase by
inserting more records in a single request.

Large batches can exceed the maximum size of a HTTP request, forcing the import
tool to split the batch into smaller requests, therefore degrading the overall
performance.

Default: 50 records per batch.

### Managing concurrency

Concurrency is configured using the `--max-requests-per-endpoint` parameter.

In practice, the number of concurrent requests is the number of
`max-requests-per-endpoint` multiplied by the number of endpoints configured by
the `endpoints` parameter.

A large number of concurrent requests can cause timeouts. When timeouts happen,
the import tool will retry failing requests applying exponential backoff to each
request.

Default: 4 concurrent requests per endpoint.

### Backoff configuration

Exponential backoff is a combination of the follow parameters:
- `network-errors-backoff-time`: The number of seconds to delay new requests
  when the network is unstable. Default: 1 second.
- `network-errors-backoff-factor`: The number to multiply
  `network-errors-backoff-time` by per network issue detected; not to exceed
  `max-network-errors-backoff-time`. Default: 2.
- `max-network-errors-backoff-time`: The maximum number of seconds to delay new
  requests when applying exponential backoff. Default: 60 seconds.
- `max-network-errors`: The maximum number of network errors tolerated within
  the configured timeframe. Default: 50 errors.
- `reset-network-errors-period`: The number of seconds the import tool will wait
  for a new network error before resetting the error count. Default: 120
  seconds.

## License

All projects in this repository are licensed under the
[Mozilla Public License](./LICENSE)
