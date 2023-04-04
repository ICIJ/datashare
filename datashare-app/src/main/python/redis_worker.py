import sys
from json import loads, dumps
from redis import Redis

import task


def main(url, queue_name):
    redis = Redis.from_url(url)
    while True:
        result = redis.blpop(queue_name, 2.0)
        if result:
            task_properties = loads(result[1])
            result = getattr(task, task_properties.get('properties')['name'])(task_properties.get('properties')).execute()
            task_properties['result'] = result
            task_properties['state'] = 'DONE'
            redis.hset('ds:task:manager', task_properties.get('name'), dumps(task_properties))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print(f'usage : {sys.argv[0]} <redis_url> <queue_name>')
        sys.exit(1)
    main(sys.argv[1], sys.argv[2])