import requests


class CountWord:
    def __init__(self, properties):
        self.properties = properties

    def execute(self):
        resp = requests.get(self.properties['url'])
        return len(resp.text.split())