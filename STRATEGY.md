
# High Level

Strategies are defined in the [TOML](https://github.com/toml-lang/toml) format. The root of a strategy consists of four elements: `config`, `inputs`, `data` and `states`. Both `inputs` and `states` are mandatory, but `config` and `data` are optional.

# `inputs` top-level field

The `inputs` element defines all of the ways in which the outside world can send data or signals to the strategy in order to make it do something. An input must have a unique name (across the inputs within the strategy) and has an associated type, which defines what kind of data it can receive and how it gets received. A strategy can have any number of inputs.

The different types of input are:

* `number` — a single numeric value which is pushed to the strategy using JSON data over HTTP webhooks.
* `bitfield` — a single numeric value that is treated as a series of boolean bits, which is pushed to the strategy using JSON data over HTTP webhooks. An input of this type must have a `fields` item, which is a list of field names, where the left-most field in the list represents the least significant bit and the right-most field in the list represents the most significant bit.
* `text` — a single text value, which is pushed to the strategy using JSON data over HTTP webhooks.
* `integer` — a single integer value, which is pushed to the strategy using JSON data over HTTP webhooks.
* `alert` — a valueless impulse, which notifies the strategy that something should happen. Alerts can be sent to the strategy as text data over HTTP webhooks, as JSON data over HTTP webhooks or as text in an email (for email alerts). The text should contain the text string `[alert id="xxx"]` somewhere, where `xxx` is the alert code to uniquely identify this particular alert. If the alert is sent as JSON, it should contain an `id` field whose value is the `xxx` alert id. An input of this type must have a `id` item, which identifies this alert and should match the `xxx` within the alert text.

Each input is identified by its key in the strategy file. For example, to create two inputs, one `number` name _"price"_ and one `alert` named _"long"_:

```toml
[inputs]
   price = {type = "number"}
   long = {type = "alert", id = "b4e33f4d-a33b-463c-a1ae-6cf69c55a59b"}
```
Since strategies are defined in TOML, there are multiple ways this could be written. For example, both of the following are equivalent to the above:
```toml
[inputs]
    [inputs.price]
        type = "number"
    [inputs.long]
        type = "alert"
        id = "b4e33f4d-a33b-463c-a1ae-6cf69c55a59b"
```
```toml
inputs.price.type = "number"
inputs.long.type = "alert"
inputs.long.id = "b4e33f4d-a33b-463c-a1ae-6cf69c55a59b"
```

## `config` top-level field

The `config` field is optional. When present, it defines an ordered list of configuration data items. These are named data values, which are globally accessible within the strategy, which are static and do not change during the executation of a strategy, but which can be set by the user on a per-instance basis.

That is, it is the static configuration which the user can set for their instance of the strategy.

Each config item has at least three fields:

 * `name` — Each config item must have a unique (amogst config values within the strategy). This is used to refer to this configuraton item elsewhere in the strategy.
 * `label` — The config label is text which succintly describes what this configuration item does and is used as a label in the strategy configuration UI.
 * `type` — This defines what kind of data this configuration item stores.

 Config items also have an optional `default` field, which defines the default value for this item.

 Config items may also have `type`-specific fields, described with the types below.

 The different possible values for `type` available are:

 * `number` — any number. An optional `precision` field defines how many decimal places this value may have (0 for integer).
 * `price` — a number representing a price
 * `percent` — a value between 0 and 100. An optional `precision` field defines how many decimal places this value may have (0 for integer).
 * `text` — any short piece of text
 * `range` — a number between a minimum and maximum. Range config items must also have a `minimum` and `maximum` fields, which define the minimum and maximum (inclusive) which this item may take on. An optional `precision` field defines how many decimal places this value may have (0 for integer).
 * `flag` — a boolean (true/false) value.
 * `choices` — a single number value, from a list of choices. The config item must also contain a list named `choice` with fields `value`, which is the value which this choice takes, and `label` which is the name presented to the user for this choice.
 * `list` — a list of items, grouped under the parent item. A list config item must also contain a list named `list-item`, where each item is itself a config item. List items are restricet in types to `number`, `price`, `percent`, `text`, `range`, `flag` and `choice` (ie, you cannot nest `list` config items).

 For example, to create configuration where the user can select an order size, a choice of order types and a list of upper and lower bounds:

```toml
[[config]]
    name = "order-size"
    label = "Order Size"
    type = "number"
    precision = 0
    default = 1000
[[config]]
    name = "order-type"
    label = "Order Type"
    type = "choice"
    default = 0
    [[config.choice]]
        label = "market"
        value = 0
    [[config.choice]]
        label = "limit"
        value = 1
[[config]]
    name = "price-thresholds"
    label = "Price Channel"
    type = "list"
    [[config.list-item]]
        label = "Upper Threshold"
        type = "price"
        default = 12000
    [[config.list-item]]
        label = "Lower Threshold"
        type = "price"
        default = 6000
```

## `data` top-level field

In strategies, while `inputs` and `config` are seen as read-only to the strategy, `data` is anything which the strategy can write to and the top-level `data` field is global data which is accessible (both readable and writeable) from any state in the strategy.

```toml
[data]
    foo = 10
    bar = [1, 2, 3]
```

## `states` top-level field

The `states` field must contain two fields, `initial`, a text value naming the initial state which a strategy starts in, and `state`, a list of state definitions.

### state

#### orders

#### triggers

 * `when`
 * `to-state`

```toml
[states]
    initial = "start"
    [[states.state]]
        id = "start"
        [[states.state.trigger]]
            when = "=> inputs.my-alert"
            to-state = "end"
    [[states.state]]
        id = "end"
```
