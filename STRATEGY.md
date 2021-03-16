* [Introduction](#Introduction)
    * [Hierarchy of States](#hierarchy-of-states)
    * [When will a Strategy Run](#when-will-a-strategy-run)
* [TOML Format](#toml-format)
* [Strategy User Guide](#strategy-user-manual)
    * [inputs](#inputs-top-level-field)
    * [config](#config-top-level-field)
    * [data](#data-top-level-field)
    * [states](#states-top-level-field)
* [Strategy Tutorial](#strategy-tutorial)
* [Scripting Reference](#scripting-reference)
    * [core](#core) functions
    * [math](#math) functions
    * [text](#text) functions
    * [list](#list) functions
    * [set](#set) functions
    * [type](#type) functions
    * [time](#time) functions
    * [trades](#trades) functions
* [Sample Strategies](#sample-strategies)

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

The `note` field allows notes to be added to the bot logs. (`note = "abc"` and `note.text = "abc"` are equivalent)

```toml
[states]
    initial = "start"

    [[states.state]]
        id = "start"
        note.text = "In state 'start'"
    
    [[states.state]]
        id = "another-state"
        data.foo = 10
        note = "=> [text: 'In another-state, value of foo is:', #foo]"
```

### **trigger**

Triggers allow the strategy to make decisions based on conditions. Triggers allow strategies to, conditionally, set data variables, add notes and change the current state to a new state.

Each trigger is a table with the following fields:

 * `when` ─ the only mandatory field is the condition to evaluate. If this evaluates to `true` then the remainder of the trigger is executed, otherwise it is ignored.
 * `to-state` ─ optional field containing the name of a state to change to.
 * `data` ─ optional field containing a table of data variables to set.
 * `note` ─ optional field containing text to add to the logs (`note = "abc"` and `note.text = "abc"` are equivalent).

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

# **Strategy Tutorial**

Lets build a strategy with the following signals:

1. Enter Long
   
   market enter long and place a stop loss a set distance away
2. Enter Short
    
    market enter short and place a stop loss a set distance away
3. Exit
    
    close the position

The entry size and stop loss distance are pre-configured values.

First, we add the configurable values:

```toml
[[config]]
    name = "entry-size"
    label = "Entry Contracts"
    type = "number"
[[config]]
    name = "stop-distance"
    label = "Stop Distance"
    type = "price"
```

This will create two variables: `config.entry-size` and `config.stop-distance`, whose values are set from the Reaper Bot UI.

Next, we add the input signals:

```toml
[inputs]
    enter-long = {type="event"}
    enter-short = {type="event"}
    exit = {type="event"}
```

This will create the variables `inputs.enter-long`, `inputs.enter-short` and `inputs.exit`. These variables will have a value of `nil`, except when a signal is received and then the variable corresponding to that signal will have a value of `true`.

Now that the configurable data and the input signals are defined (that is, all of the data that gets sent into the bot from a user-controlled source), we can think about the states that the bot can be in. Working backwards from the input signals is a good approach:

Both `enter-long` and `enter-short` enter a position, so that means we have at least two states: one where the bot is in a position and one where it is not. We will call these states `"in-position"` and `"not-in-position"`, but you could call them anything. Finally, the `exit` signal will close a position, leaving the bot in the `"not-in-position"` state. Since the bot doesn't start in a position, the initial state is the `"not-in-position"` state.


```
"not-in-position"    "in-position"
    (enter-long)  ->
    (enter-short) -> 
                  <- (exit)
```

Now that we know what states the bot will have and how it will transition between them, we can define them:

```toml
[states]
    initial = "not-in-position"

    [[states.state]]
        id = "not-in-position"

        # Here, we want to respond to the "enter-long" and "enter-short" signals
        # to place market orders

    [[states.state]]
        id = "in-position"

        # Here, we want to place a stop-loss
        # and also respond to the "exit" signal to close the position
```

Lets start with the entry signals. Its often easier to think of each of the signals separately and then clean up the strategy afterwards, so lets start with the `enter-long` signal:

```toml
[[states.state.orders]]
    # This is a market entry
    type = "market"
    # Since we want to enter long, this is a buy order
    side = "buy"
    # The number of contracts are set by config.entry-size, we need to use an expression
    contracts = "=> config.entry-size"
```

That defines the order we wish to place, when an `enter-long` signal is received. To make the order place when this happens (and not place when it doesn't happen), we can use the `when` field of the order to set a condition:

```toml
[[states.state.orders]]
    # This order only gets placed when this condition evaluates to true:
    when = "=> inputs.enter-long"
    # The rest of the order details:
    type = "market"
    side = "buy"
    contracts = "=> config.entry-size"
```

We must also give the order a tag. Every order needs to have a tag, as that is how Reaper Bot knows which order is which so that it can compare the declared orders to the existing orders in the exchange and perform the correct API requests. Since this is the `enter-long` singal, we will call this order `"Long Entry"`. Its always good to use descriptive names.

```toml
[[states.state.orders]]
    # This orders identifier:
    tag = "Long Entry"
    # The rest of the order details:
    when = "=> inputs.enter-long"
    type = "market"
    side = "buy"
    contracts = "=> config.entry-size"
```

Finally, we want to transition to the `"in-position"` state, when this order fills. This can be done using the `on` field.

```toml
[[states.state.orders]]
    tag = "Long Entry"
    when = "=> inputs.enter-long"
    type = "market"
    side = "buy"
    contracts = "=> config.entry-size"
    # Add an order-status trigger to this order:
    on.fill.to-state = "in-position"
```

Now, when this order is filled, the bot will switch states, allowing us to declare orders that should only be active when we're in a position, such as the stop loss.

The order for the short entry is almost identical: the only things that change is that `inputs.enter-long` would be replaced with `inputs.enter-short`, the `side` set to `"sell"` and the tag changed to `"Short Entry"`. So, the full `"not-in-position"` block would look like this:

```toml
[[states.state]]
    id = "not-in-position"

    [[states.state.orders]]
        tag = "Long Entry"
        when = "=> inputs.enter-long"
        type = "market"
        side = "buy"
        contracts = "=> config.entry-size"
        on.fill.to-state = "in-position"
    
    [[states.state.orders]]
        tag = "Short Entry"
        when = "=> inputs.enter-short"
        type = "market"
        side = "sell"
        contracts = "=> config.entry-size"
        on.fill.to-state = "in-position"
```

Its worth noting that there are multiple ways to achieve the same thing. Listing out each individual entry as a separate order is clear and simple and you are encouraged to do so if that makes it easier to understand what is happening. However, its also possible to achieve the same goals with a single order. First, lets start with everything that is in common between both entries:

```toml
[[states.state.orders]]
    # We can simply give it one tag
    # since we will never have more than one "open" at once
    tag = "Entry" 
    # Common details:
    type = "market"
    contracts = "=> config.entry-size"
    on.fill.to-state = "in-position"
```

The `when` condition is also straightforward: we want to place this if we get an `enter-long` **or** an `enter-short` signal:

```toml
[[states.state.orders]]
    tag = "Entry" 
    when = "=> inputs.enter-long or inputs.enter-short"
    type = "market"
    contracts = "=> config.entry-size"
    on.fill.to-state = "in-position"
```

Finally, we need to set the `side` to `"buy"` if its an `enter-long`, otherwise `"sell"`. We can easily do this with the ternary operator:

```toml
[[states.state.orders]]
    tag = "Entry" 
    when = "=> inputs.enter-long or inputs.enter-short"
    type = "market"
    side = "=> inputs.enter-long ? 'buy' : 'sell'"
    contracts = "=> config.entry-size"
    on.fill.to-state = "in-position"
```

And... that's it!

A single order is shorter and has only one place where changes might be made, making it impossible for the orders to get out of sync (that is, changing one and not the other) which may become important for complex orders. On the other hand, the single order is more complex and harder to understand at a glance, since you need to understand what the expressions are doing. This could become harder as the logic gets more complex. Its up to you to decide which one is clearer to you.

Now that the `"not-in-position"` state is complete, lets move on to `"in-position"`. The first thing we want to do when we've entered a position (using the previously defined market orders) is to place a stop loss:

```toml
[[states.state.orders]]
    tag = "Stop Loss"
    type = "stop-market"
    side = ???
    # We don't know the side! It depends on the position.
    contracts = ???
    # We don't know the contracts! It depends on the position.
    # Technically, we know that it will be config.entry-size, but by not relying
    # on that, we can make the logic work even if you allow laddered entries, take
    # profit orders etc.
    trigger-price = ???
    # We don't know the trigger price! It depends on the entry price.
    instructions = ['close']
    on.trigger.to-state = "not-in-position"
```

So, here we see some familiar fields: `tag` and `type` are set appropriately. `instruction` sets additional order execution instructions, in this case, the `close` instruction. Finally, the `on` field for reacting to status changes is used to change the state again. In this case, when the stop is `triggered` (we got stopped out of the position), then we want to transition back to the `"not-in-position"` state.

The remaining fields depend on runtime data which we don't know, so we need to use expressions to calculate the correct values.

Lets start with `side`, which is straightforward. Since this is a stop loss, its used to "exit" the position, so the side needs to be set to the opposite of what the entry order used: the side for exiting a long is `sell` and for exiting a short is `buy`. We can determine if we're in a long or a short in multiple ways.

First, you could change the entry order to set a variable. Something like:

```toml
on.fill.data.direction = "long"
```

But for this tutorial we will check the value of your position instead: if the position is greater than 0 then its a long, otherwise its a short. We can access the position using the `account.position` variable.

```toml
[[states.state.orders]]
    tag = "Stop Loss"
    type = "stop-market"
    # 'sell' if position is positive (long), otherwise 'buy'
    side = "=> account.position > 0 ? 'sell' : 'buy'"
    contracts = ???
    trigger-price = ???
    instructions = ['close']
    on.trigger.to-state = "not-in-position"
```

Similarly, we can use `account.position` to set `contracts`, since we want to close the entire position. However, simply setting `contracts` to the position directly won't work, because if this is in a short, then the position will be negative but `contracts` must always be positive. We can use the `Math.abs` function to get the *"absolute value"* of the position: that is, always get a positive value. Functions have the syntax `[function: argument]`, so we can use `[Math.abs: account.position]` to get the position as a positive number.

```toml
[[states.state.orders]]
    tag = "Stop Loss"
    type = "stop-market"
    side = "=> account.position > 0 ? 'sell' : 'buy'"
    contracts = "=> [Math.abs: account.position]"
    trigger-price = ???
    instructions = ['close']
    on.trigger.to-state = "not-in-position"
```

Finally, we want to define the trigger price as an offset from the average entry price. We can get the average entry price with `account.avg-price` and we have the offset amount in `config.stop-distance`. So the final price would be `account.avg-price - config.stop-distance` for longs and `account.avg-price + config.stop-distance` for shorts. We can simply plug that into the ternary operator similar to with `side`: `are-we-long ? (long-calculation) : (short-calculation)`

`account.position > 0 ? (account.avg-price - config.stop-distance) : account.avg-price + config.stop-distance`

But we can do slightly better, by returning negative `config.stop-distance` for long and positive for short:

`account.avg-price + (account.position > 0 ? -config.stop-distance : config.stop-distance)`

And plugging that into the order, we get:

```toml
[[states.state.orders]]
    tag = "Stop Loss"
    type = "stop-market"
    side = "=> account.position > 0 ? 'sell' : 'buy'"
    contracts = "=> [Math.abs: account.position]"
    trigger-price = "=> account.avg-price + (account.position > 0 ? -config.stop-distance : config.stop-distance)"
    instructions = ['close']
    on.trigger.to-state = "not-in-position"
```

Great! The final piece of the puzzle is the `exit` signal, which works like a mashup of the entry signals and the stop loss. Copying the entry market order and tweaking it slightly, we get:

```toml
[[states.state.orders]]
    tag = "Exit" 
    when = "=> inputs.exit"
    type = "market"
    side = ???
    contracts = ???
    on.fill.to-state = "not-in-position"
```

And we already know how to set side and contracts from the stop loss.

```toml
[[states.state.orders]]
    tag = "Exit" 
    when = "=> inputs.exit"
    type = "market"
    side = "=> account.position > 0 ? 'sell' : 'buy'"
    contracts = "=> [Math.abs: account.position]"
    on.fill.to-state = "not-in-position"
```

So, the complete `"in-position"` state looks like this:

```toml
[[states.state]]
    id = "in-position"

    [[states.state.orders]]
        tag = "Stop Loss"
        type = "stop-market"
        side = "=> account.position > 0 ? 'sell' : 'buy'"
        contracts = "=> [Math.abs: account.position]"
        trigger-price = "=> account.avg-price + (account.position > 0 ? -config.stop-distance : config.stop-distance)"
        instructions = ['close']
        on.trigger.to-state = "not-in-position"

    [[states.state.orders]]
        tag = "Exit" 
        when = "=> inputs.exit"
        type = "market"
        side = "=> account.position > 0 ? 'sell' : 'buy'"
        contracts = "=> [Math.abs: account.position]"
        on.fill.to-state = "not-in-position"
```

**And we're done!**

Putting it altogether, here is the full strategy:

```toml
[[config]]
    name = "entry-size"
    label = "Entry Contracts"
    type = "number"
[[config]]
    name = "stop-distance"
    label = "Stop Distance"
    type = "price"

[inputs]
    enter-long = {type="event"}
    enter-short = {type="event"}
    exit = {type="event"}

[states]
    initial = "not-in-position"

    [[states.state]]
        id = "not-in-position"

        [[states.state.orders]]
            tag = "Long Entry"
            when = "=> inputs.enter-long"
            type = "market"
            side = "buy"
            contracts = "=> config.entry-size"
            on.fill.to-state = "in-position"
        
        [[states.state.orders]]
            tag = "Short Entry"
            when = "=> inputs.enter-short"
            type = "market"
            side = "sell"
            contracts = "=> config.entry-size"
            on.fill.to-state = "in-position"

    [[states.state]]
        id = "in-position"

        [[states.state.orders]]
            tag = "Stop Loss"
            type = "stop-market"
            side = "=> account.position > 0 ? 'sell' : 'buy'"
            contracts = "=> [Math.abs: account.position]"
            trigger-price = "=> account.avg-price + (account.position > 0 ? -config.stop-distance : config.stop-distance)"
            instructions = ['close']
            on.trigger.to-state = "not-in-position"

        [[states.state.orders]]
            tag = "Exit" 
            when = "=> inputs.exit"
            type = "market"
            side = "=> account.position > 0 ? 'sell' : 'buy'"
            contracts = "=> [Math.abs: account.position]"
            on.fill.to-state = "not-in-position"
```

Or for the single-entry version:

```toml
[[config]]
    name = "entry-size"
    label = "Entry Contracts"
    type = "number"
[[config]]
    name = "stop-distance"
    label = "Stop Distance"
    type = "price"

[inputs]
    enter-long = {type="event"}
    enter-short = {type="event"}
    exit = {type="event"}

[states]
    initial = "not-in-position"

    [[states.state]]
        id = "not-in-position"

        [[states.state.orders]]
            tag = "Entry" 
            when = "=> inputs.enter-long or inputs.enter-short"
            type = "market"
            side = "=> inputs.enter-long ? 'buy' : 'sell'"
            contracts = "=> config.entry-size"
            on.fill.to-state = "in-position"

    [[states.state]]
        id = "in-position"

        [[states.state.orders]]
            tag = "Stop Loss"
            type = "stop-market"
            side = "=> account.position > 0 ? 'sell' : 'buy'"
            contracts = "=> [Math.abs: account.position]"
            trigger-price = "=> account.avg-price + (account.position > 0 ? -config.stop-distance : config.stop-distance)"
            instructions = ['close']
            on.trigger.to-state = "not-in-position"

        [[states.state.orders]]
            tag = "Exit" 
            when = "=> inputs.exit"
            type = "market"
            side = "=> account.position > 0 ? 'sell' : 'buy'"
            contracts = "=> [Math.abs: account.position]"
            on.fill.to-state = "not-in-position"
```

# **Scripting Reference**

The scripting *expressions* allow you to generate values dynamically.

**Data Types:**

* **number** ─ integer or decimal numbers like `10`, `-4`, `3.14`
* **boolean** ─ `true` or `false`
* **text** ─ text like `'Hello, world!'`
* **list** ─ a list of values like `[1, 2, 3, 4]`
* **nil** ─ represents empty or non-existent data, has only one value: `nil`.

**Truthiness:**

The values `false` and `nil` count as "false" and all other values count as "true".

That means that if a condition (eg the `when` field of an order) evaluates to either `false` or `nil`, then that condition is counted as failed (the order with that `when` won't get generated) and any other value of any other data type is counted as true.

**Unary Operators:**

* **`-`** ─ negate a number: `-(1 + 1)` ⇨ `-2`
* **`not`** ─ negate a boolean: `not true` ⇨ `false`, `not (1 == 2)` ⇨ `true`

**Binary Operators:**

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

**Ternary Operator:**

The ternary operator has the following syntax:

```
condition ? true-branch : false-branch
```

For example:

```toml
data.foo = "=> inputs.signal ? 10 : 20
```

If the `signal` input has been received, `foo` is set to `10`, otherwise it's set to `20`.


**Functions:**

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

```
[max: val1, val2, ..., valN]
```

`[max: 4, 2, 5, 3, 1]` ⇨ `5`

Returns the maximum value of its arguments.

* **min**

```
[min: val1, val2, ..., valN]
```

`[min: 4, 2, 5, 3, 1]` ⇨ `1`

Returns the minimum value of its arguments.

* **text**

```
[text: val1, val2, ..., valN]
```

`[text: 'a=', 5, ' b=', 7]` ⇨ `'a=5 b=7'`

Convert arguments to text and combine them into a single text string.

* **format**

```
[format: format-string, val1, val2, ..., valN]
```

`[format: 'a=%d b=%d x=%s', 5, 7, 'ABC']` ⇨ `'a=5 b=7 x=ABC'`

Create formatted text. The first argument is the *"format string"* that controls the format and the remaining are its arguments. [See list of formatting options](http://download.oracle.com/javase/1.5.0/docs/api/java/util/Formatter.html).

* **value**

```
[value: val1, val2, ..., valN]
```

`[value: nil, nil, 5, nil]` ⇨ `5`

Returns the first non-nil value from its arguments.

## **Math**

* **floor**
```
[Math.floor: value]
```
`[Math.floor: 1.3]` = `1`

Round a float *down* to an integer (rounding towards negative infinity).

* **ceil**
```
[Math.ceil: value]
```
`[Math.ceil: 1.3]` = `2`

Round a float *up* to an integer (rounding towards positive infinity).

* **round**
```
[Math.round: value]
```
`[Math.round: 1.3]` = `1`
`[Math.round: 1.7]` = `2`
`[Math.round: 1.5]` = `2`

Round a float to the closest integer (ties are rounded *away* from zero).

* **truncate**
```
[Math.truncate: value]
```
`[Math.truncate: 1.8]` = `1`

Convert a float to an integer by discarding the decimal part and keeping only the whole number part without rounding.

* **abs**
```
[Math.abs: value]
```
`[Math.abs: -12]` = `12`

Returns the absolute value (positive) of the input value.

* **sign**
```
[Math.sign: value]
```
`[Math.sign: -43]` = `-1`

Returns `1` if the value is positive, `-1` if its negative or `0` if its `0`.

* **is-positive**
```
[Math.is-positive: value]
```
`[Math.is-positive: 12]` = `true`

Test if a value is positive.

* **is-negative**
```
[Math.is-negative: value]
```
`[Math.is-negative: -12]` = `true`

Test if a value is negative.

* **is-zero**
```
[Math.is-zero: value]
```
`[Math.is-zero: 0]` = `true`

Test if a value is zero.

* **is-nonzero**
```
[Math.is-nonzero: value]
```
`[Math.is-nonzero: 12]` = `true`

Test if a value is not zero.

* **is-even**
```
[Math.is-even: value]
```
`[Math.is-even: 12]` = `true`

Test if a value is even.

* **is-odd**
```
[Math.is-odd: value]
```
`[Math.is-odd: 11]` = `true`

Test if a value is odd.

## **Text**

* **upper-case**
```
[Text.upper-case: text]
```
`[Text.upper-case: 'abc']` = `'ABC'`

Conversts text to upper case.

* **lower-case**
```
[Text.lower-case: text]
```
`[Text.lower-case: 'ABC']` = `'abc'`

Conversts text to lower case.

* **capitalize**
```
[Text.capitalize: text]
```
`[Text.capitalize: 'abc']` = `'Abc'`

Conversts the first letter of text to upper case.

* **join**
```
[Text.join: list, separator]
```
`[Text.join: [1, 2, 3], ':']` = `'1:2:3`

Joins all items in a list, as text, placing `separator` between each item.

* **slice**
```
[Text.slice: text, from, length]
```
`[Text.slice: 'abcdef', 2, 3]` = `'cde'`

Return a section of `length` letters from the input text, starting at `from` (zero-indexed, ie the first letter is 0).

If `length` is longer than the available letters, then the available letters are returned: `[Text.slice: 'abcdef', 2, 10]` = `'cdef'`

* **split**
```
[Text.split: text, pattern]
```
`[Text.split: 'abc:def', ':']` = `['abc', 'def']`

Split text into a list of sections.

* **find**
```
[Text.find: text, pattern]
```
`[Text.find: 'abcdef', 'cd']` = `2`

Return the (zero-indexed) position of `pattern` in `text`, or `-1` if it is not found.

## **List**

* **new**
* **from**
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
```
[Set.new: a, b, ...]
```
Create a new set of values.

* **from**
```
[Set.from: collection]
```
Converts a collection (list, map or set) into a set. This will de-duplicate values in the collection!

* **union**
```
[Set.union: a, b]
```
Returns a new set containing all values from both `a` and `b`, with duplicates removed.

* **intersection**
```
[Set.intersection: a, b]
```
Returns a new set containing only values that are in both `a` and `b`.

* **difference**
```
[Set.difference: a, b]
```
Returns a new set containing all values from `a` that are also in `b`.

## **Type**

* **is-integer**
```
[Type.is-integer: value]
```

`[Type.is-integer: 10]` = `true`
`[Type.is-integer: 1.2]` = `false`

* **is-float**
```
[Type.is-float: value]
```

`[Type.is-float: 1.2]` = `true`
`[Type.is-float: 10]` = `false`

* **is-number**
```
[Type.is-number: value]
```

`[Type.is-number: 1.2]` = `true`
`[Type.is-number: 10]` = `true`

* **is-boolean**
```
[Type.is-boolean: value]
```
`[Type.is-boolean: false]` = `true`

* **is-text**
```
[Type.is-text: value]
```
`[Type.is-text: 'hello']` = `true`

* **is-list**
```
[Type.is-list: value]
```
`[Type.is-list: [1, 2, 3]]` = `true`

* **is-table**
```
[Type.is-table: value]
```
`[Type.is-table: {key: 12}]` = `true`

* **is-nil**
```
[Type.is-nil: value]
```
`[Type.is-nil: nil` = `true`

## **Time**

**NOTE: All times are in UTC!**

* **since**
```
[Time.since: time]
```
`[Time.since: time-2h-17m-5s-ago]` => `{days: 0, hours: 2, minutes: 137, seconds: 8225}`

Gives the duration from a specified time (as a seconds timestamp -- the `time` variable gives you the current time in this format).
You can access the duration as number of days (`[Time.since: ...].days`), number of hours (`[Time.since: ...].hours`), number of minutes (`[Time.since: ...].minutes`) or number of seconds (`[Time.since: ...].seconds`).

Note that `[Time.since: time].seconds` = `0` (zero seconds have passed since "now").

* **day-of-month**
```
[Time.day-of-month: time]
```
Converts a time (as a "seconds" timestamp -- the `time` variable gives the current time in this format) to the day of the month.

* **day-of-week**
```
[Time.day-of-week: time]
```
Converts a time (as a "seconds" timestamp -- the `time` variable gives the current time in this format) to the day of the week, as upper case text (eg `'TUESDAY'`).

* **hour-of-day**
```
[Time.hour-of-day: time]
```
Converts a time (as a "seconds" timestamp -- the `time` variable gives the current time in this format) to the hour of the day it falls on.

* **minute-of-hour**
```
[Time.minute-of-hour: time]
```
Converts a time (as a "seconds" timestamp -- the `time` variable gives the current time in this format) to the minute of the hour of the day it falls on.

* **second-of-minute**
```
[Time.second-of-minute: time]
```
Converts a time (as a "seconds" timestamp -- the `time` variable gives the current time in this format) to the second of the minute of the hour of the day it falls on.

## **Trades**

* **max-contracts**
```
[Trades.max-contracts: balance, price]
```
`[Trades.max-contracts: 1000, 10]` ⇨ `100`

Calculate the maximum number of contracts that you can buy with the specified balance and price, taking fees into account.

* **price-offset**
```
[Trades.price-offset: percent, price]
```
Calculate a new price offset from the specified price by percent.

* **risk-based-contracts**
```
[Trades.risk-based-contracts: balance, price, stop-price, percentage-loss]
```
Calculate number of contracts for an entry that can be placed at *price*, using *balance*, with a stop loss placed at *stop-price* such that if the stop fills, exactly *percentage-loss*% of balance would be lost. Use this to calculate entry sizes for risk-based entries, allowing you to cap the potential per-trade losses to *percentage-loss*.

* **position-side**
```
[Trades.position-side: value]
```
Returns `value` as a positive number if current position is long, a negative number if current position is short or 0 if not in a position.

# **Sample Strategies**

The following strategy has two input signals, `enter-long` and `enter-short`. These signals enter a position and then place a stop loss and two take profit orders. If already in a short when an `enter-long` signal is received, then the existing position is closed, and vice versa.

```toml
# Configurable settings
[[config]]
    name = "order-size"
    label = "Order Size"
    type = "number"
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