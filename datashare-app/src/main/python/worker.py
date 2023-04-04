import sys

import requests


class CountWord:
    def __init__(self, properties):
        self.properties = properties

    def execute(self):
        resp = requests.get(self.properties['url'])
        return len(resp.text.split())


def main(url):
    while True:
        resp = requests.get(url) # blocks until data is available
        if resp.status_code != 404:
            task_properties = resp.json()
            result = getattr(sys.modules[__name__], task_properties.get('properties')['name'])(task_properties.get('properties')).execute()
            task_properties['result'] = result
            task_properties['state'] = 'DONE'
            requests.post(url.replace("next", "save"), json=task_properties)


if __name__ == '__main__':
    if len(sys.argv) != 2:
        print(f'usage : {sys.argv[0]} <ds_poll_url>')
        sys.exit(1)
    main(sys.argv[1])