int maxOffset = params._source.content.length();
int end = params.offset+params.limit;
try {
    String contentResized = params._source.content.substring(params.offset, end);
    return [
        "content": contentResized,
        "maxOffset":maxOffset,
        "offset":params.offset,
        "limit":params.limit
    ];
} catch (StringIndexOutOfBoundsException e) {
    return ["error":'Range ['+params.offset+'-'+end+'] is out of document range ([0-'+maxOffset+'])', "code": 400];
}
