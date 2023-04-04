import sys
import task
import requests


def main(url):
    while True:
        resp = requests.get(url) # blocks until data is available
        if resp.status_code != 404:
            task_properties = resp.json()
            result = getattr(task, task_properties.get('properties')['name'])(task_properties.get('properties')).execute()
            task_properties['result'] = result
            task_properties['state'] = 'DONE'
            requests.post(url.replace("next", "save"), json=task_properties)


if __name__ == '__main__':
    if len(sys.argv) != 2:
        print(f'usage : {sys.argv[0]} <ds_poll_url>')
        sys.exit(1)
    main(sys.argv[1])