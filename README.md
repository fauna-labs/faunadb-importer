# FaunaDB Importer

FaunaDB Importer is a command line utility to help users import static data into
[FaunaDB](https://fauna.com/product). It works both for Cloud and for on
premisses clusters.

Supported files:
- JSON
- CSV
- TSV

## Usage

Download the [latest version](https://github.com/fauna/faunadb-importer/releases)
and extract the zip file. Inside the extracted folder, run:

```
./bin/faunadb-importer \
  import-file \
  --secret <your-keys-secret-here> \
  --class <your-class-name> \
  <file-to-import>
```

For example:

```
./bin/faunadb-importer \
  import-file \
  --secret "abc" \
  --class users \
  data/users.json
```

The importer will load all data into the specified class, preserving the fields'
names and types as they are described in the import file.

You can also type `./bin/faunadb-importer --help` for more detailed information.

## How it works

The importer is a stateful process separated in two phases: the id generation,
and the data import.

At first, the importer will parse all records and generate a fauna id for each
record. Pre-generating ids beforehand allows us to import schemas containing
relational data while keeping foreign keys consistent. It also ensures that we
can safely re-run the process without the risk of duplicating information.

In order map legacy ids to new generated fauna ids, the importer will:
- Check if there is a field configured with the `ref` type. The field's value
  will be used as the lookup term for the new fauna id.
- If no field is configured with the`ref` type, the importer assign a sequential
  number for each record as the lookup term for the new fauna id.

Once this phase completes, the pre-generated ids will be stored at `cache/ids`.
In case of a re-run, the importer will load the ids from disk and skip this
phase.

At the second phase, the importer will insert all records into FaunaDB, using
the pre-generated id for each record as its [ref](https://fauna.com/documentation/queries#values-special_types)
field.

At this phase, if the import fails to run due to a data inconsistency, it is:
- __NOT__ safe to change fields configured with the `ref` type as they will be
  used as the lookup term for the pre-generated id from the first phase.
- __NOT__ safe to remove entries from the import file if you don't have a field
  configured as a `ref` field as it alters the sequential number assigned to the
  record.
- __SAFE__ to fix data inconsistencies in any field besides fields configured with
  the `ref` type.

As long as you keep `cache/ids` intact, it is safe to re-run the process until
the import completes. That means that if you want to use the importer again with
a different input file, you __must__ clean the `cache` folder first.

### File structure

```
.
├── README.md                    # This file
├── bin                          #
│   ├── faunadb-importer         # Unix startup script
│   └── faunadb-importer.bat     # Winddows startup script
├── cache                        # Where the importer saves its cache
├── data                         # Where you should copy the files you whish to import
├── lib                          #
│   └── faunadb-importer-1.0.jar # The importer library
└── logs                         # Logs for each execution
```

## Advanced Usage

### Configuring fields

When importing `JSON` files, fields' names and types are optional but, when
importing text files, you must specify each field's name and type in order using
the `--format` option, like:

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

When importing a `JSON` file where the root element of the file is a `array` or,
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

You can ignore fields with `--ignore-fields` option. For example:

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
be used as the lookup term when resolving its fauna id but, we're configuring it
as an ignored field so we omit its value from the imported data.

### How to keep data in chronological order

You can keep chronological order when importing data by using the `--ts-field`
option. For example:

```
./bin/faunadb-importer \
  import-file \
  --secret "abc" \
  --class users \
  --ts-field "created_at"
  data/users.csv
```

The field's value configured in the `--ts-field` option will be used as the `ts`
field for the imported instance.

### Importing to your on cluster

By default, the importer will load your data into FaunaDB Cloud. If you whish to
import the data to your on cluster, you can use the `--endpoints` option. For
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

## License

All projects in this repository are licensed under the
[Mozilla Public License](./LICENSE)
