docker run -it \
  --name remote-vibe-dev \
  -e HOME=/workspace \
  -v /home/acorim/aco/projects/dockers/tools/remote-viber:/workspace \
  --network container:proxy-gateway \
  agy:latest


# agy --dangerously-skip-permissions