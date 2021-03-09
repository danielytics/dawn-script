
# **Introduction**

Strategies are defined in the [TOML](https://toml.io/en/) format. The root of a strategy consists of four elements: `config`, `inputs`, `data` and `states`. Both `inputs` and `states` are mandatory, but `config` and `data` are optional.

* `config` defines variables which should be set by the end user, and will appear in the Reaper Bot UI. An example might be order entry size, or number of take profit limit orders.
* `inputs` define signals that are used as input to drive the strategy forward. Typically this would be external signals (eg from TradingView) that tell the bot that something should happen.
* `data` is an optional collection of variables that you can read and modify as part of the strategies logic.
* `states` is a collection of (hierarchical) states that the bot can be in. Each state defines its data, triggers and orders. Logic can be added to switch from one state to another when certain conditions are fulfilled. This is the means of adding logic to the strategy.

## **Hierarchy of States**

Strategies consist of a hierarchy of **states** and one state is active at any given time. The active state inherits all of the data, triggers and orders from all of its parent states in the hierarchy. For example, you could have a strategy with 4 states: `not-in-position`, `in-position`, `in-long`, and `in-short`, where `in-long` and `in-short` are children of the `in-position` state. Visually, this would look something like this:

```
├─ not-in-position
└─ in-position
    ├─ in-long
    └─ in-short
```

In this example, both `in-long` and `in-short` inherit data, triggers and orders from `in-position` (but can also override them, if desired, as well as add their own).

Each **state** defines a template of the desired state of the world. Strategies are *declarative*, meaning that you declare what you want and the system figures out what actions need to be performed to change the world (ie your open orders, position, etc) into what you declared that you want. Practically speaking, this means that each *state* defines a collection of orders that you wish to have placed, but it can also include data that you want to store and logic for transitioning from one state to another.

An example of this is shown below:

```
├─ not-in-position
│     ORDERS:
│      ├─ Market buy order (when input.enter-long signal)
│      │    On fill: change state to "in-long"
│      │
│      └─ Market sel order (when input.enter-short signal)
│           On fill: change state to "in-short"
│ 
└─ in-position
    │ ORDERS:
    │  └─ Stop-loss limit order
    │       On fill: change state to "not-in-position"
    │
    ├─ in-long
    │     ORDERS:
    │      └─ Limit sell order (Take Profit)
    │           On fill: change state to "not-in-position"
    │
    └─ in-short
          ORDERS:
           └─ Limit buy order (Take Profit)
                On fill: change state to "not-in-position"
```

In this example, we use the same states as before, but this time we show how they could declare orders. The bot would start in "*not-in-position*" and place two orders (but only when the appropriate signal is received). So if the "*enter-long*" signal is received, this bot would place a buy market order and when that order fills, the bot will change into the "*in-long*" state. Since the "*in-long*" state is a child of the "*in-position*" state, it inherits all of the orders from "*in-position*" and would therefore place two orders: a stop loss and a limit sell take profit order. When either of these orders get filled, the bot changes back into the "*not-in-position*" state (cancelling the other remaining open order in the process).

This example uses three states to represent when the bot is in a position, however, simple cases like this example can be easily done using a single state for the position and using dynamic orders instead. This will be explained later and multiple states were used here to illustrate the hierarchical nature. In a real strategy, you should use whichever method makes the strategy easier to understand. Typically, its a tradeoff between duplication and complexity, as will be illustrated later in this documentation.

From the example, we see that the strategy declares what orders you want for each state. When the strategy is run, the output is the collection of these orders. The bot will then compare these *expected* orders with the actual *existing* orders in your exchange account and it will generate a sequence of changes necessary to turn *existing* into *expected*. These changes are either *placing new orders*, *cancelling existing orders* or *modifying existing orders* (or in the case where modifying is impossible, cancelling the old order and placing a new one to replace it).

For example, if you have the following open orders in your exchange account (*existing*):

```
Type    Side    Contracts   Price   Tag

Limit   Buy     100         10,000  A
Limit   Buy     200         15,000  B
Limit   Sell    500         25,000  C
```

and the strategy declares this collection of orders (*expected*):

```
Type    Side    Contracts   Price   Tag

Limit   Sell    25          20,000  D
Limit   Buy     100         15,000  B
Limit   Sell    500         25,000  C
```

then the bot would generate the following order requests for the exchange:

```
PLACE Limit (side=Sell, contracts=25, price=20000, order=D)
CANCEL Limit (order=A)
MODIFY Limit (contracts=25, order=B)
```

That is, it cancels any orders that are in *existing* but not in *expected* (A), it places orders that are in *expected* but not in *existing* (D) and it modifies orders that are in both *existing* and *expected*, but where the attributes have changed. Order C is in both *existing* and *expected* and the attributes are unchanged, so this order is left in place untouched.

An important detail to note is that every order must have a (unique) `tag` property, which is used to identify each order. An *expected* order with the same tag as an *existing* order is assumed to be the same order, so any changed attributes are changes that should be applied to the *existing* order (either by modifying it, or by replacing it with a new order). Its therefore a good idea to use a descriptive tag (eg call the stop loss *"stop loss"* or similar).

As states can define orders, this can be used to swap which orders you expect to be active and since child states inherit orders from their parent states (if any), you can use this to declare common orders that should not get cancelled between state changes. Alternatively, you can simply declare an order with the same tag.

That covers the basic concept of how Reaper Bot strategies declare and execute orders. The exact semantics are described below, as well as a reference of all the different features and attributes.

## **When will a strategy run?**

A strategy in Reaper Bot doesn't continuously run, but instead it only does so when something "triggers" it to run. There are two possible means of triggering a bot to execute its strategy:

* **Signals**
    
    When the bot receives a signal (from an external source such as TradingView, or an internal signal), it is compared to a list of signals which the bot is "watching" and if the bot is "watching" for that signal, then the bot will execute the strategy.
    
    A bot is "watching" a signal if that signal's input variable is read from in the currently active state. That means that receiving a signal which isn't being used in the script won't cause the strategy to be executed, unless that input is marked to do so (explained below). 

* **Order updates**

    When an orders status changes (eg an order is filled or cancelled), if the strategy declares logic to run when this happens, then the bot will execute the strategy. This way, logic can be attached to orders.

* **Running a stopped bot**

    When a bot that wasn't running is set as running, the strategy is executed.

**It is important to remember that the bot will not execute the strategy unless it is triggered to do so by one of the above methods.** That means that if you set (for example) an order to place when a condition is true (eg price is greater than some value), that order doesn't get placed when the condition is true, unless the bot is also triggered. The order is only placed if the condition holds while the strategy is being executed using one of the above triggering mechanisms.

In the future, a means of triggering directly from conditions on market data (eg price moves) will likely be added, but until then, it is important to remember that the bot doesn't run until something makes it run, regardless of the conditions you have set up.


# **TOML Format**

Strategies are defined in the [TOML](https://toml.io/en/) format, extended to allow for any value to be an expression that is evaluated to generate the actual value.

For a whirlwind guide to the TOML format, see [Learn TOML in Y minutes](https://learnxinyminutes.com/docs/toml/).

While TOML allows you to assign various types of values to fields, it doesn't provide any way of adding logic, conditions or dynamic data. Reaper Bot extends TOML by adding *expressions*: formula's that are evaluated to generate the fields value dynamically, possibly based on external data such as market data or signals. Think of these expressions like Excel formula's. A cell in Excel can have a simple value (numbers, text etc) just like a field in TOML, but a cell can also contain a formula that gets evaluated to calculate the value.

Reaper Bot allows this by setting the field to a *text string* that starts with `=>`. The remainder of that *text string* is treated as a formula:

```toml
[my-table]
    my-field = "=> (3 + 7) / 2"
```

When evaluated, this would become equivalent to:

```toml
my-table.my-field = 5
```

The full expression language is described below.

A realistic example of a TOML file defining part of a real strategy is shown below:

```toml
[states]
    initial = "not-in-position"

    # When not in a position:
    [[states.state]]
        id = "not-in-position"

        [[states.state.orders]]
            # Only place this order when entering long
            when = "=> inputs.enter-long and account.position < 0"
            # Every order needs a unique tag
            tag = "close short"
            # Order properties
            type = "market"
            side = "buy"
            # Calculate contracts from account position ([Math.abs: -10] returns +10)
            contracts = "=> [Math.abs: account.position]"

        [[states.state.orders]]
            # Only place this order when entering long
            when = "=> inputs.enter-long"
            # Every order needs a unique tag
            tag = "long"
            # Order properties
            type = "market"
            side = "buy"
            contracts = 1000
            # When this order has filled, switch to the "in-position" state
            on.fill.to-state = "in-position"
```

# **Strategy User Manual**

With the introduction out of the way, lets move on to specifics. Below you will find a reference of all fields and the values they may have. Exact semantics of how strategies are executed are also included where they apply.

---
## **`inputs` top-level field**

The `inputs` element defines all of the ways in which the outside world can send data or signals to the strategy in order to make it do something. An input must have a unique name (across the inputs within the strategy) and has an associated type, which defines what kind of data it can receive and how it gets received. A strategy can have any number of inputs.

---
**WARNING: This section is not currently valid, ignore for now ─ just set all inputs to type "event"...**

The different types of input are:

* `number` ─ a single numeric value which is pushed to the strategy using JSON data over HTTP webhooks.
* `bitfield` ─ a single numeric value that is treated as a series of boolean bits, which is pushed to the strategy using JSON data over HTTP webhooks. An input of this type must have a `fields` item, which is a list of field names, where the left-most field in the list represents the least significant bit and the right-most field in the list represents the most significant bit.
* `text` ─ a single text value, which is pushed to the strategy using JSON data over HTTP webhooks.
* `integer` ─ a single integer value, which is pushed to the strategy using JSON data over HTTP webhooks.
* `event` ─ an impulse, which notifies the strategy that something should happen. Alerts can be sent to the strategy as text data over HTTP webhooks, as JSON data over HTTP webhooks or as text in an email (for email alerts). The text should contain the text string `[alert id="xxx"]` somewhere, where `xxx` is the alert code to uniquely identify this particular alert. If the alert is sent as JSON, it should contain an `id` field whose value is the `xxx` alert id. An input of this type must have a `id` item, which identifies this alert and should match the `xxx` within the alert text.

**...End of section**

---
Each input is identified by its key in the strategy file. For example, to create two inputs, one `number` named _"price"_ and one `alert` named _"long"_:

```toml
[inputs]
   price = {type = "number"}
   long = {type = "event"}
```
Since strategies are defined in TOML, there are multiple ways this could be written. For example, both of the following are equivalent to the above:
```toml
[inputs]
    [inputs.price]
        type = "number"
    [inputs.long]
        type = "event"
```
```toml
inputs.price.type = "number"
inputs.long.type = "event"
```

When a strategy is evaluated, inputs can be accessed as follows:

```toml
[inputs]
    enter-long = {type = "event"}

# ...

something = "=> inputs.enter-long"
```

That is, `inputs.<name>` can be used in an *expression* to access the input named `<name>`.

---
## **`config` top-level field**

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

 **WARNING: Currently only number, price, percent, and list are implemented.**

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

When a strategy is evaluated, config values can be accessed as follows:

```toml
[[config]]
    name = "order-size"
    label = "Order Size"
    type = "number"
    precision = 0
    default = 1000

# ...

something = "=> config.order-size"
```

That is, `config.<name>` can be used in an *expression* to access the config named `<name>`.

---
## **`data` top-level field**

In strategies, while `inputs` and `config` are seen as read-only to the strategy, `data` is anything which the strategy can write to and the top-level `data` field is global data which is accessible (both readable and writeable) from any state in the strategy.

```toml
[data]
    foo = 10
    bar = [1, 2, 3]
```

When a strategy is evaluated, data values can be accessed as follows:

```toml
[data]
    my-value = 100

# ...

something = "=> #my-value"
```

That is, `#<name>` can be used in an *expression* to access the data named `<name>`. 

Any variables beginning with a `#` are *dynamic* (it can change its value while the strategy is being evaluated) while everything else (`inputs.*`, `config.*` etc) is *static* (a value is set once before the strategy is evaluated and then retains that value until evaluation completed). Static data can have different values for different times the strategy is evaluated, but during evaluation, it does not change. Dynamic data can be changed while the strategy is evaluated.


---
## **`states` top-level field**

The `states` field must contain two fields, `initial`, a text value naming the initial state which a strategy starts in, and `state`, a list of state definitions.

```toml
[states]
    initial = "start"

    [[states.state]]
        id = "start"
```

## **state**

The `state` field is a list of individual states. Each state **must** have an `id` field, which is the name of that state.

The `id` field is the only mandatory field, every other field is optional. These optional fields are:

* `data` ─ Like the top-level `data` field, this allows you to assign values to variables that can be read elsewhere. The values are retained even when states are changed, unless the new states overwrites them.
* `note` ─ Allows a note to be added to the bots log. Notes may reference variables and these can be set in the `data` section.
* `trigger` ─ A list of tables representing triggers. A trigger is a condition which, when true, will *trigger* the bot to perform an action, such as changing into a new state.
* `orders` ─ A list of tables representing orders. These orders will be synchronised with the exchange by placing, modifying or cancelling exchange orders.

### **Evaluation Order**

When a strategy is executed, the order in which the states sections are evaluated is important. The order is as follows:

When entering a new state:

1. Data
2. Notes
3. Triggers
4. Orders

When the existing state is executed:

1. Triggers
2. Orders

This means that data can be used to initialise variables when a state is entered and these values will be retained, even if their code relied on other variables that have since changed (eg market data). The variables can be updated without changing state through triggers or order updates.

### **data**

The `data` field allows data variables to be created or modified.

```toml
[data]
    counter = 5

[states]
    initial = "start"

    [[states.state]]
        id = "start"
        # Use 'data' to create a new data variable called 'test' and set it to 1
        data.test = 1
        # Use 'data' to increment the value of counter by 1
        data.counter = "=> #counter + 1"
```

### **note**

The `note` field allows notes to be added to the bot logs.

```toml
[states]
    initial = "start"

    [[states.state]]
        id = "start"
        note.text = "In state 'start'"
    
    [[states.state]]
        id = "another-state"
        data.foo = 10
        note.text = "=> [text: 'In another-state, value of foo is:', #foo]"
```

### **trigger**

Triggers allow the strategy to make decisions based on conditions. Triggers allow strategies to, conditionally, set data variables, add notes and change the current state to a new state.

Each trigger is a table with the following fields:

 * `when` ─ the only mandatory field is the condition to evaluate. If this evaluates to `true` then the remainder of the trigger is executed, otherwise it is ignored.
 * `to-state` ─ optional field containing the name of a state to change to.
 * `data` ─ optional field containing a table of data variables to set.
 * `note.text` ─ optional field containing text to add to the logs.

```toml
[states]
    initial = "start"
    [[states.state]]
        id = "start"
        note.text = "In state 'start'"
        [[states.state.trigger]]
            when = "=> inputs.my-alert"
            note.text = "Changing to state 'end'"
            to-state = "end"
    [[states.state]]
        id = "end"
        note.text = "In state 'end'"
```

When this strategy runs initially, it will be in state `start` and the `In state 'start'` text will be added to the notes. If `my-alert` is `true` (for example, because the signal was received), then the note `Changed to state 'end'` will be added and the state will change to `end`. Finally, since the strategy is now in the `end` state, the note `In state 'end'` is added. The logs would therefore contain the following:

```
In state 'start'
Changing to state 'end'
In state 'end'
```

In reality, there may be other items in the logs too, that were added by the system.

### **orders**

The `orders` field is a list of tables, where each one represents an order (or multiple orders in some cases). Strategies exist to generate these orders, as that is the only way for the strategy to communicate with the exchange.

Orders have many fields:

* `tag` ─ A name uniquely identifying this order. If, later, another order is generated with the same tag, it is assumed to be the same order (but possibly with different properties)
* `when` ─ A condition. When this is `true`, the order is generated and when its `false` the order is skipped (and cancelled if an existing order with the same tag is open). Defaults to `true`, so omitting this field will always generate the order.
* `type` ─ The order type. One of: `"market"`, `"limit"`, `"stop-market"`, `"stop-limit"`
* `side` ─ One of: `"buy"`, `"sell"`
* `contracts` ─ The size of the order, in contracts
* `price` ─ For limit orders only, the price at which to place the order
* `trigger-price` ─ For stop orders only, the price at which to trigger the stop
* `trail-offset` ─ For trailing stop orders, the amount to trail behind the price by
* `instructions` ─ A list of execution instructions, such as `"close"`, `"reduce-only"`, `"post-only"`.
* `auto-replace` ─ Set to `true` for this order to get automatically replaced if it is no longer open (eg because it got filled or cancelled). By default, this value is assumed to be `false`.
* `on` ─ A table where each field represents a status change trigger, executed when this order changes status. Available statuses are `on.fill`, `on.cancel` and `on.trigger`. The body of each of these is a table with the same structure as a normal trigger, but without the `when` condition.
* `foreach` ─ A special field used to generate multiple orders from a single *template* order. Explained in detail below.

Some examples of orders:

```toml
[data]
    fill-time = 0

# ...

[[states.state.orders]]
    # Every order needs a tag
    tag = "my market entry"
    # This is a market order
    type = "market"
    # This order is for 1000 contracts
    contracts = 1000
    # When this order is filled, change state
    on.fill.to-state = "in-position"

[[states.state.orders]]
    # Every order needs a tag
    tag = "my long entry"
    # Only place this order when a signal is received
    when = "=> inputs.long-entry"
    # This is a market order
    type = "market"
    # This order is for 10% of account balance contracts
    contracts = "=> 10% of [Trades.max-contracts: account.balance, market.last-price]"

[[states.state.orders]]
    # Every order needs a tag
    tag = "my limit order"
    # This is a limit order
    type = "limit"
    # This order is for a configurable amount of contracts
    contracts = "=> config.entry-size"
    # Set this order to post-only/ParticipateDoNotInitiate
    instructions = ["post-only"]
    # Only replace this order 10 minutes after the last one filled/cancelled
    # [Time.since: #fill-time] returns the amount of time that has passed since #fill-time
    # [Time.minutes: ...] converts the time duration into minutes
    auto-replace = "=> [Time.minutes: [Time.since: #fill-time]] >= 10"
    # When this order is filled, remember the time
    on.fill.data.fill-time = "=> time"
```

The `foreach` field is special. It provides a means of generating multiple orders from a single *template order*. It works by creating a data variable for each value in a list and then generating a new order for each one. For example:

```toml
[[states.state.orders]]
    # Duplicate this order for each of the items in the list, and assign that value to #x
    foreach.x = [1, 2, 3, 4, 5]
    # Each order has a tag "order/1", "order/2" etc
    tag = "=> [text: 'order/', #x]"
    # Only place this order for even elements of the list
    when = "=> [Math.is-even: #x]"
    # This is a market order
    type = "market"
    # This order is for 20 or 40 orders, depending on #x
    contracts = "=> #x * 10"
```

This will generate two orders:

```
Tag       Type     Contracts

order/2   market   20
order/4   market   40
```

# **Scripting Reference**

The scripting *expressions* allow you to generate values dynamically.

Data Types:

* **number** ─ integer or decimal numbers like `10`, `-4`, `3.14`
* **boolean** ─ `true` or `false`
* **text** ─ text like `'Hello, world!'`
* **list** ─ a list of values like `[1, 2, 3, 4]`
* **nil** ─ represents empty or non-existent data, has only one value: `nil`.

Unary Operators:

* **`-`** ─ negate a number: `-(1 + 1)` ⇨ `-2`
* **`not`** ─ negate a boolean: `not true` ⇨ `false`, `not (1 == 2)` ⇨ `true`

Binary Operators:

* **`+`** ─ Add two numbers: `3 + 2` ⇨ `5`
* **`-`** ─ Subtract two numbers: `3 - 2` ⇨ `1`
* **`*`** ─ Multiply two numbers: `3 * 2` ⇨ `6`
* **`/`** ─ Divide two numbers: `6 / 2` ⇨ `3`
* **`mod`** ─ Modular arithemetic. Divide two numbers, returning the remainder: `5 mod 3` ⇨ `2`
* **`pow`** ─ Exponentiation: `4 pow 3` ⇨ `64`
* **`and`** ─ Logical [AND](https://en.wikipedia.org/wiki/Truth_table#Logical_conjunction_(AND)) of two booleans: `true and false` ⇨ `false`
* **`or`** ─ Logical [OR](https://en.wikipedia.org/wiki/Truth_table#Logical_disjunction_(OR)) of two booleans: `true or false` ⇨ `false`
* **`xor`** ─ Logical [XOR](https://en.wikipedia.org/wiki/Truth_table#Exclusive_disjunction) of two booleans: `true xor true` ⇨ `false`
* **`==`** ─ Compare two items for equality: `3 == 2` ⇨ `false`
* **`!=`** ─ Compare two items for inequality: `3 != 2` ⇨ `true`
* **`>`** ─ Test if one item is greater than another: `2 > 3` ⇨ `false`
* **`>=`** ─ Test if one item is greater than or equal to another: `3 >= 3` ⇨ `true`
* **`<`** ─ Test if one item is less than another: `2 < 3` ⇨ `true`
* **`<=`** ─ Test if one value is less than or equal to another: `2 <= 1` ⇨ `false`
* **`in`** ─ Test if an item is contained in a list: `3 in [1, 2, 3, 4]` ⇨ `true`
* **`++`** ─ Concatenate two lists: `[1, 2] ++ [3, 4]` ⇨ `[1, 2, 3, 4]`
* **`% of`** ─ Percentage: `25% of 200` ⇨ `50`, `(10 + 5)% of (50 * 8)` ⇨ `60`

Ternary Operator:

The ternary operator has the following syntax:

```
condition ? true-branch : false-branch
```

For example:

```toml
data.foo = "=> inputs.signal ? 10 : 20
```

If the `signal` input has been received, `foo` is set to `10`, otherwise it's set to `20`.


Functions:

Reaper Bot provides a library of built in functions that can be called to perform calculations or operations. Functions have the following basic syntax:

```
[function-name: list-of-arguments]
```

A function with no arguments would be called as `[function-name:]`, a function with one argument as `[function-name: 123]` and a function with two arguments as `[function-name: 123, 456]`.

Some "core" functions can be called just like that: `[text: 'a', 1]` ⇨ `'a1'`, while most functions exist in a "package": `[Math.abs: -100]` ⇨ `100`, in this case, `Math` is the package and `abs` is a function in that package.

Functions can be used anywhere a value could be used: `"=> (10 * [Math.abs: -23]) mod 2"` ⇨ `0`

The following packages of functions exist:

* **Core** ─ The most commont "core" functions, which do not require the package to be specified in the function call.
* **Math**  ─ Common mathematical operations
* **Text**  ─ Text manipulation
* **List**  ─  Create, use, access, modify or otherwise manipulate lists
* **Set**  ─  Set union, intersection and difference operations
* **Type**  ─ Functions to test what type a value is
* **Time**  ─ Functions for comparing and accessing time
* **Trades**  ─ Special purpose trading functions

A reference of all functions in these packages is below:

## **Core**

* **max**

`[max: 4, 2, 5, 3, 1]` ⇨ `5`

Returns the maximum value of its arguments.

* **min**

`[min: 4, 2, 5, 3, 1]` ⇨ `1`

Returns the minimum value of its arguments.

* **text**

`[text: 'a=', 5, ' b=', 7]` ⇨ `'a=5 b=7'`

Convert arguments to text and combine them into a single text string.

* **format**

`[format: 'a=%d b=%d x=%s', 5, 7, 'ABC']` ⇨ `'a=5 b=7 x=ABC'`

Create formatted text. The first argument is the *"format string"* that controls the format and the remaining are its arguments. [See list of formatting options](http://download.oracle.com/javase/1.5.0/docs/api/java/util/Formatter.html).

* **value**

`[value: nil, nil, 5, nil]` ⇨ `5`

Returns the first non-nil value from its arguments.

## **Math**

* **floor**
* **ceil**
* **round**
* **truncate**
* **abs**
* **sign**
* **is-positive**
* **is-negative**
* **is-zero**
* **is-nonzero**
* **is-even**
* **is-odd**

## **Text**

* **upper-case**
* **lower-case**
* **capitalize**
* **join**
* **slice**
* **split**
* **find**

## **List**

* **new**
* **find**
* **filled**
* **append**
* **concat**
* **take**
* **drop**
* **first**
* **second**
* **last**
* **get**
* **sum**
* **sort**
* **reverse**
* **set**
* **set-in**
* **transform**
* **keep**
* **remove**
* **collect**
* **avg**
* **take-last**
* **drop-last**
* **range**
* **indexed**
* **max**
* **min**
* **is-empty**
* **count**
* **zip**
* **unzip**

## **Set**

* **new**
* **union**
* **intersection**
* **difference**

## **Type**

* **is-integer**
* **is-float**
* **is-number**
* **is-boolean**
* **is-text**
* **is-list**
* **is-table**
* **is-nil**

## **Time**

* **now**
* **since**
* **days**
* **hours**
* **minutes**
* **day-of-week**
* **hour-of-day**
* **minute-of-day**
* **second-of-day**

## **Trades**

* **max-contracts**
* **price-offset**
* **risk-based-contracts**


# **Sample Strategies**

The following strategy has two input signals, `enter-long` and `enter-short`. These signals enter a position and then place a stop loss and two take profit orders. If already in a short when an `enter-long` signal is received, then the existing position is closed, and vice versa.

```toml
# Configurable settings
[[config]]
    name = "order-size"
    label = "Order Size"
    type = "price"
[[config]]
    name = "stop-distance"
    label = "Stop Distance"
    type = "price"
[[config]]
    name = "tp-distances"
    label = "Take Profit Distances"
    type = "list"
    [[config.list-value]]
        label = "First TP"
        type = "price"
        default = 100
    [[config.list-value]]
        label = "Second TP"
        type = "price"
        default = 200

# Input signals
[inputs]
    enter-long = {type = "event"}
    enter-short = {type = "event"}

# Strategy logic
[states]
    initial = "not-in-position"

    # When not in a position:
    [[states.state]]
        id = "not-in-position"

        [[states.state.orders]]
            # Only place this order when entering long
            when = "=> inputs.enter-long and account.position < 0"
            # Every order needs a unique tag
            tag = "close short"
            # Order properties
            type = "market"
            side = "buy"
            contracts = "=> [Math.abs: account.position]"

        [[states.state.orders]]
            # Only place this order when entering long
            when = "=> inputs.enter-long"
            # Every order needs a unique tag
            tag = "long"
            # Order properties
            type = "market"
            side = "buy"
            contracts = "=> config.order-size"
            # When this order has filled, switch to the "in-position" state
            on.fill.to-state = "in-position"
        
        [[states.state.orders]]
            # Only place this order when entering short
            when = "=> inputs.enter-short and account.position > 0"
            # Every order needs a unique tag
            tag = "close long"
            # Order properties
            type = "market"
            side = "sell"
            contracts = "=> [Math.abs: account.position]"

        [[states.state.orders]]
            # Only place this order when entering short
            when = "=> inputs.enter-short"
            # Every order needs a unique tag
            tag = "short"
            # Order properties
            type = "market"
            side = "sell"
            contracts = "=> config.order-size"
            # When this order has filled, switch to the "in-position" state
            on.fill.to-state = "in-position"
    
    # When in a position:
    [[states.state]]
        id = "in-position"

        data.exit-direction = "=> account.position > 0 ? 'sell' : 'buy'"

        # Set state back to "not-in-position" when position is closed
        [[states.state.trigger]]
            when = "=> account.position == 0"
            to-state = "not-in-position"

        # Place stop loss
        [[states.state.orders]]
            tag = "stop-loss"
            type = "stop-market"
            side = "=> #exit-direction"
            contracts = "=> [Math.abs: account.position]"
            trigger-price = "=> account.position > 0 ? (account.avg-price - config.stop-distance) : (account.avg-price + config.stop-distance)"
            instructions = ['close']
            on.trigger.note = "Stop Triggered"
            on.trigger.to-state = "not-in-position"
        
        # Place take profit limit orders, each for half of the position
        [[states.state.orders]]
            # Duplicate this order for each of the TP distance values
            foreach.tp = "=> config.tp-distances"
            tag = "=> [text: 'tp/', #tp]"
            type = "limit"
            side = "=> #exit-direction"
            contracts = "=> config.order-size / 2"
            price = "=> account.position > 0 ? (account.avg-price + #tp) : (account.avg-price - #tp)"
            instructions = ['reduce-only', 'post-only']
            # By subscribing to the fill event, we make sure that the above trigger will get run when a TP fills (order of execution: event -> data -> triggers -> orders)
            on.fill.note = "TP Filled"

        # Close position on exit signal
        [[states.state.orders]]
            when = "=> inputs.exit-position"
            tag = "exit"
            type = "market"
            side = "=> #exit-direction"
            contracts = "=> account.position"
            instructions = ['close']
            on.fill.to-state = "not-in-position"

```