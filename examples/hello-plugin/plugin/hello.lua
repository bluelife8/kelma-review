local messages = require("kelma_example.messages")

kelma.commands.register("tech.kelma.example.hello.greet", "Greet from Lua", function(arguments)
  return {
    message = messages.greeting(arguments.name or "Kelma"),
    runtime = _VERSION,
  }
end)

kelma.events.subscribe("app.started", function(event)
  kelma.log.info("Received " .. event.name)
end)

kelma.events.subscribe("review.completed", function(event)
  kelma.log.info("Review rating: " .. tostring(event.attributes.rating))
end)

kelma.ui.register_renderer("tech.kelma.example.hello.border", function(request)
  return {
    html = '<div class="hello-plugin">' .. request.html .. "</div>",
    css = request.css .. ".hello-plugin{border:1px solid #c9ac6b;padding:12px}",
  }
end)
