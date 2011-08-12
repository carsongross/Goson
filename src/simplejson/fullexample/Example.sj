{
  "some_type": {
    "big_int_ex": "biginteger",
    "string_ex": "string",
    "boolean_ex": "boolean",
    "big_decimal_ex": "bigdecimal",
    "int_ex": "integer",
    "decimal_ex": "decimal",
    "type_in_array": [{
      "content": "string"
    }],
    "map_ex": { 
      "map_of" : { 
        "key" : "biginteger", 
        "value" : "string" 
      } 
    },
    "enum_ex": { "enum" : ["json", "txt", "xml", "jsd", "wtf"] },
    "nested_type": {
      "nested_string_ex": "string",
      "nested_type_in_array": [{
        "value": "string",
        "a_date": "date"
      }],
      "big_int_array_ex": ["biginteger"],
      "string_array_ex": ["string"],
      "nested_big_int_ex" : "biginteger",
      "nested_big_decimal_ex" : "bigdecimal"
    }
  }
}