FROM golang

ARG GOOS=darwin
ARG GOARCH=amd64

ADD go-whisk $GOPATH/src/github.ibm.com/BlueMix-Fabric/go-whisk
ADD go-whisk-cli $GOPATH/src/github.ibm.com/BlueMix-Fabric/go-whisk-cli

RUN cd $GOPATH/src/github.ibm.com/BlueMix-Fabric/go-whisk-cli/ && go get
RUN env GOOS=$GOOS GOARCH=$GOARCH go build -o $GOPATH/bin/wsk -i $GOPATH/src/github.ibm.com/BlueMix-Fabric/go-whisk-cli/main.go
