package tech.kelma.app

/** Host-owned Lua prelude installed before a plugin entrypoint executes. */
internal val KelmaLuaBootstrap: String =
    """
    local host = kelma
    local json_null = setmetatable({}, { __tostring = function() return "null" end })
    local json_array = {}
    local json_object = {}

    local function encode_string(value)
      local replacements = {
        ['"'] = '\\"', ['\\'] = '\\\\', ['\b'] = '\\b', ['\f'] = '\\f',
        ['\n'] = '\\n', ['\r'] = '\\r', ['\t'] = '\\t'
      }
      return '"' .. value:gsub('[%z\1-\31\\"]', function(character)
        return replacements[character] or string.format('\\u%04x', string.byte(character))
      end) .. '"'
    end

    local function encode(value, seen, depth)
      if value == json_null or value == nil then return 'null' end
      local kind = type(value)
      if kind == 'boolean' then return value and 'true' or 'false' end
      if kind == 'number' then
        if value ~= value or value == math.huge or value == -math.huge then
          error('JSON cannot encode non-finite numbers')
        end
        return string.format('%.17g', value)
      end
      if kind == 'string' then return encode_string(value) end
      if kind ~= 'table' then error('JSON cannot encode ' .. kind) end
      if depth >= 64 then error('JSON nesting is too deep') end
      if seen[value] then error('JSON cannot encode cyclic tables') end
      seen[value] = true
      local count, maximum = 0, 0
      local array = getmetatable(value) == json_array
      local explicit_object = getmetatable(value) == json_object
      for key in pairs(value) do
        count = count + 1
        if type(key) == 'number' and key >= 1 and key % 1 == 0 then maximum = math.max(maximum, key)
        elseif not explicit_object then array = false end
      end
      if not explicit_object and getmetatable(value) == nil and count > 0 and maximum == count then array = true end
      local parts = {}
      if array and maximum == count then
        for index = 1, maximum do parts[index] = encode(value[index], seen, depth + 1) end
        seen[value] = nil
        return '[' .. table.concat(parts, ',') .. ']'
      end
      for key in pairs(value) do
        if type(key) ~= 'string' then error('JSON object keys must be strings') end
      end
      local keys = {}
      for key in pairs(value) do keys[#keys + 1] = key end
      table.sort(keys)
      for index, key in ipairs(keys) do
        parts[index] = encode_string(key) .. ':' .. encode(value[key], seen, depth + 1)
      end
      seen[value] = nil
      return '{' .. table.concat(parts, ',') .. '}'
    end

    local function decode(source)
      if type(source) ~= 'string' then error('JSON input must be a string') end
      local position, length = 1, #source
      local parse_value
      local function fail(message) error(message .. ' at JSON byte ' .. position, 0) end
      local function skip_space()
        while position <= length and source:sub(position, position):match('%s') do position = position + 1 end
      end
      local function parse_string()
        if source:sub(position, position) ~= '"' then fail('expected string') end
        position = position + 1
        local result = {}
        while position <= length do
          local character = source:sub(position, position)
          position = position + 1
          if character == '"' then return table.concat(result) end
          if character == '\\' then
            local escape = source:sub(position, position)
            position = position + 1
            local simple = { ['"']='"', ['\\']='\\', ['/']='/', b='\b', f='\f', n='\n', r='\r', t='\t' }
            if simple[escape] then result[#result + 1] = simple[escape]
            elseif escape == 'u' then
              local digits = source:sub(position, position + 3)
              if not digits:match('^%x%x%x%x$') then fail('invalid Unicode escape') end
              position = position + 4
              local codepoint = tonumber(digits, 16)
              if codepoint >= 0xD800 and codepoint <= 0xDBFF and source:sub(position, position + 1) == '\\u' then
                local low = tonumber(source:sub(position + 2, position + 5), 16)
                if low and low >= 0xDC00 and low <= 0xDFFF then
                  codepoint = 0x10000 + (codepoint - 0xD800) * 0x400 + low - 0xDC00
                  position = position + 6
                end
              end
              result[#result + 1] = utf8.char(codepoint)
            else fail('invalid string escape') end
          else
            if string.byte(character) < 32 then fail('control character in string') end
            result[#result + 1] = character
          end
        end
        fail('unterminated string')
      end
      local function parse_number()
        local start = position
        local value = source:sub(position):match('^-?%d+%.?%d*[eE]?[+-]?%d*')
        if not value or value == '' then fail('invalid number') end
        position = position + #value
        local number = tonumber(value)
        if not number then fail('invalid number') end
        return number
      end
      local function parse_array()
        position = position + 1
        local result = setmetatable({}, json_array)
        skip_space()
        if source:sub(position, position) == ']' then position = position + 1 return result end
        while true do
          result[#result + 1] = parse_value()
          skip_space()
          local separator = source:sub(position, position)
          position = position + 1
          if separator == ']' then return result end
          if separator ~= ',' then fail('expected comma or closing bracket') end
        end
      end
      local function parse_object()
        position = position + 1
        local result = setmetatable({}, json_object)
        skip_space()
        if source:sub(position, position) == '}' then position = position + 1 return result end
        while true do
          skip_space()
          local key = parse_string()
          skip_space()
          if source:sub(position, position) ~= ':' then fail('expected colon') end
          position = position + 1
          result[key] = parse_value()
          skip_space()
          local separator = source:sub(position, position)
          position = position + 1
          if separator == '}' then return result end
          if separator ~= ',' then fail('expected comma or closing brace') end
        end
      end
      parse_value = function()
        skip_space()
        local character = source:sub(position, position)
        if character == '"' then return parse_string() end
        if character == '{' then return parse_object() end
        if character == '[' then return parse_array() end
        if source:sub(position, position + 3) == 'true' then position = position + 4 return true end
        if source:sub(position, position + 4) == 'false' then position = position + 5 return false end
        if source:sub(position, position + 3) == 'null' then position = position + 4 return json_null end
        return parse_number()
      end
      local result = parse_value()
      skip_space()
      if position <= length then fail('trailing JSON content') end
      return result
    end

    host.json = {
      null = json_null,
      array = function(value) return setmetatable(value or {}, json_array) end,
      object = function(value) return setmetatable(value or {}, json_object) end
    }
    host.json.encode = function(value) return encode(value, {}, 0) end
    host.json.decode = decode
    host._encode = host.json.encode
    host._decode = host.json.decode
    host.commands = { register = host._register_command }
    host.events = { subscribe = host._subscribe_event }
    host.ui = { register_renderer = host._register_renderer }
    host.log = {}
    for _, level in ipairs({'debug', 'info', 'warn', 'error'}) do
      host.log[level] = function(message) host._write_log(level, tostring(message)) end
    end
    _G.print = function(...)
      local values = {}
      for index = 1, select('#', ...) do values[index] = tostring(select(index, ...)) end
      host._write_log('info', table.concat(values, '\t'))
    end
    """.trimIndent()
